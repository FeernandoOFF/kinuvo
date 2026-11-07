package kinuvo.harness.server

import kinuvo.harness.server.devices.*
import kinuvo.harness.server.reflection.*
import kinuvo.harness.server.screens.*
import co.touchlab.stately.collections.ConcurrentMutableMap
import co.touchlab.stately.collections.ConcurrentMutableSet
import co.touchlab.stately.concurrency.Lock
import co.touchlab.stately.concurrency.withLock
import io.github.oshai.kotlinlogging.KotlinLogging
import kinuvo.harness.core.Agent
import kinuvo.harness.server.execution.*
import kinuvo.harness.execution.api.*
import kinuvo.harness.core.Verdict
import kinuvo.harness.core.agenttools.AgentSummary
import kinuvo.harness.core.agenttools.CallerScope
import kinuvo.harness.core.tools.Tool
import kinuvo.harness.server.mcp.AgentToolGateway
import kinuvo.harness.server.mcp.McpAccess
import kinuvo.harness.server.mcp.McpEndpoint
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.serializer
import kotlinx.serialization.json.JsonObject
import kinuvo.harness.core.routines.NEW_SESSIONS
import kinuvo.harness.core.routines.with
import kinuvo.protocol.*
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.decrementAndFetch
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.time.TimeSource
import kotlinx.coroutines.*
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.concurrent.Volatile
import kotlin.uuid.Uuid

private val log = KotlinLogging.logger("kinuvo.server")

@OptIn(ExperimentalAtomicApi::class)
class ServerRuntime(
    val harness: HarnessRuntime,
    val auth: PeerRegistry,
    private val name: String = "kinuvo",
    private val foregroundOnly: Boolean = false,
    private val security: ConnectionSecurity = ConnectionSecurity(),
    executionRegistry: ExecutionRegistry = ExecutionRegistry(),
    private val executionFactory: ExecutionFactory? = null,
    private val executionStorage: ExecutionRecoveryStore = MemoryExecutionRecovery(),
    val remoteLinks: RemoteLinks = RemoteLinks(),
    private val prepareAttachment: (UploadAttachmentParams) -> UploadAttachmentParams = { it },
    /** Held while a live voice call runs, so the host keeps serving it in real time. */
    private val keepAwake: () -> AutoCloseable = { AutoCloseable {} },
    /** The MCP endpoint's tokens; the host that listens sets where harnesses reach it. */
    val mcp: McpAccess = McpAccess(),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val executions = executionRegistry
    private val remoteFactory = RemoteExecutionFactory(remoteLinks, harness.serverId) { id, online, code ->
        val before = executions.catalog().connections.firstOrNull { it.config.id == id }
        if (before != null) {
            if (online) executions.negotiated(id, ExecutionCapabilities(cancel = true, questions = true, approvals = true))
            else executions.offline(id, if (code == "access_revoked") "Remote access revoked" else "Remote Server offline", code)
            if (before != executions.catalog().connections.firstOrNull { it.config.id == id }) catalogChanged()
        }
    }
    private val installedFactory = object : ExecutionFactory {
        override suspend fun probe(config: HarnessConnection) = if (config.connection is ExecutionConnection.Remote) remoteFactory.probe(config) else executionFactory?.probe(config) ?: ExecutionProbe(ExecutionReadiness(true, false, "ACP unavailable", "unsupported"))
        override suspend fun open(config: HarnessConnection, registration: AgentRegistration, state: ExecutionState, broker: ExecutionBroker, scope: CoroutineScope) =
            open(config, registration, state, broker, scope, null)
        override suspend fun open(config: HarnessConnection, registration: AgentRegistration, state: ExecutionState, broker: ExecutionBroker, scope: CoroutineScope, tools: KinuvoMcp?) =
            if (config.connection is ExecutionConnection.Remote) remoteFactory.open(config, registration, state, broker, scope) else requireNotNull(executionFactory).open(config, registration, state, broker, scope, tools)
    }
    private val remoteGateway = RemoteGateway(remoteLinks,
        { admin.agents() + externalAgents() },
        { id -> createSession(id).let { sourceSnapshot(id, it.id) } },
        { agent, id -> sourceSnapshot(agent, id) },
        { agent, id, text, action -> send(SendParams(agent, id, text, action = action)); Unit },
        { _, id -> stop(id) },
        { agent, answer -> execution(agent).answer(answer) },
        { _, id -> if (external.owns(id)) external.discard(id) else stop(id) })
    private val external = ExecutionCoordinator(executions, installedFactory, scope, harness.log, executionStorage,
        resolve = { harness.attachments.download(it) }, readinessChanged = { catalogChanged() }, mcp = mcp)
    private val agentTools = AgentToolGateway(harness) { scope, tool, args, verdict -> askExternal(scope, tool, args, verdict) }
    /** Kinuvo's tools for External Agents; the host serves it at `/mcp`. */
    val mcpEndpoint = McpEndpoint(mcp, agentTools)
    suspend fun persistExecutionAttachment(state: ExecutionState, data: AttachmentData): Attachment {
        external.currentStateOwner(state)
        require(attachmentMimeType(data.attachment.name) == data.attachment.mimeType) { "Output filename and MIME differ" }
        return harness.attachments.upload(UploadAttachmentParams(data.attachment.name, data.base64)).also {
            require(it.mimeType == data.attachment.mimeType) { "Stored output MIME differs" }
        }
    }
    private val screenBudget = ScreenBudget()
    private val clients = ConcurrentMutableSet<Client>()
    private val runs = Runs(harness.log, scope)
    private val nativeExecution = NativeExecutionDispatch(
        { harness.newSession(agent(it)) },
        { agentId, id ->
            val snapshot = harness.log.executionSnapshot(agent(agentId), id)
            if (snapshot.phase == ExecutionPhase.Idle && runs.isActive(id)) snapshot.copy(phase = ExecutionPhase.Running) else snapshot
        },
        { answer -> answerNative(answer) },
        { sendNative(it) }, runs::stop,
    )
    private val devicePush = devicePush(auth, harness.serverId)
    private val notifications = Notifications(harness, scope, runs::routine, { external.registrationOrArchived(it)?.name }, devicePush)
    private val scheduler = Scheduler(harness, runs, scope, onRan = { broadcast(Api.RoutineRan, it); notifications.routineRan(it) })
    private val admin = Admin(harness, runs) { broadcast(Api.AgentsChanged, it) }
    private val reflectionReview = ReflectionReview(harness)
    private val logins = ProviderLogins(harness.modelProviders, scope) { broadcast(Api.ProvidersChanged, it); changedCapabilities() }
    private val reflector = Reflector(harness, scope, runs::isActive)
    private val dispatcher = TaskDispatcher(harness, runs, scope)
    private val seq = AtomicLong(0)
    private val voice = VoiceRequests(harness, ::changedCapabilities)
    private val knowledge = KnowledgeRequests(harness)
    private val tasks = TaskRequests(harness)
    private val settings = SettingsRequests(harness, logins, ::agent, notifications)
    private var scheduling = false
    private var started = false
    private var closed = false
    private val lifecycle = Mutex()
    private val executionManagement = Mutex()

    val capabilities get() = ServerCapabilities(harness.serverId, harness.features + setOf(ServerFeature.Screens) + (if (auth.pairable) setOf(ServerFeature.Pairing) else emptySet()) + (if (devicePush != null) setOf(ServerFeature.Push) else emptySet()),
        if (foregroundOnly) ExecutionLifetime.Foreground else ExecutionLifetime.Continuous,
        if (scheduling) WakeSupport.Scheduled else WakeSupport.None, integrations = (harness.integrations + (if (executionFactory != null) mapOf(Integration.Harness to setOf("acp", "remote")) else mapOf(Integration.Harness to setOf("remote")))).mapKeys { it.key.name }, platform = harness.platform, voice = voice.providers.setup)
    /** Runs going and the events they've produced; a foreground-only host keeps them alive while it can. */
    val runActivity get() = runs.activity

    /** With nobody watching, runs paused on an Approval or a question can't go on, so they stop, now or once they pause; the rest keep going. */
    private suspend fun stopWaiting() {
        val waiting = runs.sessions().filter { harness.log.status(it) == SessionStatus.Waiting }
        waiting.forEach(runs::stop)
        executions.catalog().agents.flatMap { harness.store.sessions(it.id) }.filter { harness.log.status(it.id) == SessionStatus.Waiting }.forEach { external.stop(it.id) }
        waiting.forEach { runs.join(it) }
    }

    private fun changedCapabilities() = broadcast(Api.CapabilitiesChanged, Unit)
    init {
        harness.delegation = ServerDelegation(harness, runs, external, { executions.registration(it) != null }, ::agent, ::createSession, { send(it) }, ::stop)
        harness.externalAgents = {
            val catalog = executions.catalog()
            catalog.agents.mapNotNull { a ->
                catalog.connections.firstOrNull { it.config.id == a.connectionId }?.config?.takeIf { it.connection is ExecutionConnection.Acp }
                    ?.let { AgentSummary(a.id, a.name, "External Agent on ${it.name}", ExecutionKind.Acp, null, null, false) }
            }
        }
        harness.log.executionMetadata = { info -> if (external.owns(info.id)) info.copy(legacyOrder = external.record(info.id).legacyOrder) else info }
        harness.browser?.authorize { owner ->
            browserOwner(owner)
            check("browser" in agent(owner.agentId).config.tools) { "Browser is disabled for this Agent" }
        }
        harness.log.onDelete = { a, id -> external.delete(a, id) }; harness.computers.browserService = { harness.browser }; harness.devices.paired = auth::toolDevices; harness.computers.paired = auth::toolDevices
        if (foregroundOnly) harness.log.onChange { if (it.status == SessionStatus.Waiting && unwatched()) scope.launch { stop(it.id) } }
    }

    private fun unwatched() = clients.block { it.none { c -> c.watching } }

    suspend fun start(schedule: Boolean = false) = lifecycle.withLock {
        check(!closed) { "Server is closed" }
        check(!started) { "Server already started" }
        started = true
        try {
            (executionStorage as? ExecutionStorage)?.migrate(harness.log)?.forEach { log.error { it } }
            external.reload()
            external.recover()
            scope.launch { harness.usage.filterNotNull().collect { broadcast(Api.Usage, it) } }
            harness.log.onChange { broadcast(Api.SessionChanged, it) }
            harness.log.onEvent(notifications::on)
            harness.mcp.onSignInNeeded(notifications::mcpSignInNeeded)
            harness.routines.onChange { agent, routines -> broadcast(Api.RoutinesChanged, RoutinesChanged(agent.id, routines)) }
            harness.knowledge.onChange { broadcast(Api.KnowledgeChanged, it) }
            harness.tasks.onChange { broadcast(Api.TaskChanged, it) }
            harness.artifacts.onChange { broadcast(Api.ArtifactChanged, it) }
            harness.browser?.onChange { broadcast(Api.BrowserChanged, it); broadcast(Api.ScreensChanged, it) }
            harness.computers.onChange = { broadcast(Api.ComputersChanged, it); broadcast(Api.ScreensChanged, null) }
            harness.modelProviders.mapNotNull { it.downloads }.forEach { downloads -> scope.launch {
                var installed: Set<String>? = null
                downloads.changes.collect { state ->
                    broadcast(Api.ProvidersChanged, logins.list())
                    val now = state.models.filter { it.installed }.mapTo(mutableSetOf()) { it.id }
                    if (installed != null && now != installed) changedCapabilities()
                    installed = now
                }
            } }
            if (schedule) { scheduler.start(); dispatcher.start() }
            scheduling = schedule
            for (connection in executions.catalog().connections) {
                executions.offline(connection.config.id, "Checking harness", "checking")
                scope.launch {
                    try { checkHarness(connection.config) }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { log.warn(e) { "Harness check failed: ${connection.config.name}" } }
                }
            }
        } catch (e: Throwable) {
            try { shutdown() } catch (cleanup: Throwable) { e.addSuppressed(cleanup) }
            throw e
        }
    }

    suspend fun stopRuns() = runs.stopAll()
    suspend fun close() = withContext(NonCancellable) { lifecycle.withLock { shutdown() } }

    private suspend fun shutdown() = withContext(NonCancellable) {
        if (closed) return@withContext
        closed = true
        try {
            external.close()
            runs.stopAll()
            clients.block { it.toList() }.forEach { it.disconnect() }
        } finally {
            try { scope.coroutineContext[Job]?.cancelAndJoin() } finally { try { devicePush?.close() } finally { harness.close() } }
        }
    }

    private inner class Client(private val connection: FrameConnection) : SessionLog.Subscriber {
        var liveVoice: LiveVoiceSession? = null
        var callScope: CoroutineScope = scope
        val browser = connection.credential != null
        val outbox = Channel<Frame>(OUTBOX_SIZE)
        val liveAudio = AtomicInt(0)
        val screenFrames = ScreenFrameLane()
        val screens = Screens(harness.computers, { harness.browser }, ::browserOwner, scope, { requireNotNull(deviceId) }, screenBudget,
            screenFrames::offer, { screenFrames.remove(it.subscription); offer(frame(Api.ScreenStatus, it)) })
        @Volatile override var watching = true
        @Volatile var token: String? = null
        private val credentialLock = Lock()
        var linked = false
        fun valid() = credentialLock.withLock { token?.let { if (linked) remoteLinks.exposure(it) != null else auth.valid(it) } != false }
        fun <T> authenticated(block: (String) -> T): T = credentialLock.withLock { block(requireNotNull(token)) }
        var deviceId: String? = null
        var tools: Set<DeviceToolKind> = emptySet()
        @Volatile var closeAfterResponse: String? = null
        private val pending = ConcurrentMutableMap<String, CompletableDeferred<Frame.Response>>()
        val readDevice: suspend (DeviceToolRequest) -> DeviceToolResult = { p ->
            val id = Uuid.random().toString()
            val response = CompletableDeferred<Frame.Response>()
            pending[id] = response
            try {
                check(offer(Frame.Request(id, Api.DeviceRead.name, ProtocolJson.encodeToJsonElement(Api.DeviceRead.params, p)))) { "Device disconnected" }
                val result = response.await()
                check(result.ok) { "Device read failed" }
                ProtocolJson.decodeFromJsonElement(Api.DeviceRead.result, result.payload)
            } finally { pending.remove(id) }
        }

        val actComputer: suspend (ComputerRequest) -> ComputerResult = { p ->
            val id = Uuid.random().toString()
            val response = CompletableDeferred<Frame.Response>()
            pending[id] = response
            try {
                check(offer(Frame.Request(id, Api.ComputerAct.name, ProtocolJson.encodeToJsonElement(Api.ComputerAct.params, p)))) { "Device disconnected" }
                val result = response.await()
                check(result.ok) { result.error ?: "The Device couldn't do that" }
                ProtocolJson.decodeFromJsonElement(Api.ComputerAct.result, result.payload)
            } finally { pending.remove(id) }
        }

        val watcher: (ComputerFrame) -> Unit = { offer(frame(Api.ComputerFrameEvent, it)) }

        fun response(response: Frame.Response) { pending.remove(response.id)?.complete(response) }

        fun refreshTools(tools: Set<DeviceToolKind> = this.tools, initial: Boolean = false) = harness.devices.lock.withLock {
            if (!initial && deviceId?.let { harness.devices.owns(it, readDevice) } != true) return@withLock
            this.tools = tools
            val id = auth.setTools(requireNotNull(token), tools)
            deviceId = id
            harness.devices.connect(id, tools, watching, readDevice)
            if (DeviceToolKind.Computer in tools) harness.computers.connect(id, actComputer) else harness.computers.disconnect(id, actComputer)
        }

        fun detachTools() {
            harness.computers.unwatchAll(watcher)
            harness.devices.lock.withLock { deviceId?.let { harness.devices.disconnect(it, readDevice); harness.computers.disconnect(it, actComputer) } }
            pending.block { map -> map.values.toList().also { map.clear() } }.forEach { it.completeExceptionally(IllegalStateException("Device disconnected")) }
        }

        override fun offer(update: SessionUpdate) = offer(frame(Api.Update, update))

        fun offer(text: Frame): Boolean {
            if (linked && text is Frame.Event) return true
            if (!valid()) { if (closeAfterResponse == null) disconnect(); return false }
            if (outbox.trySend(text).isSuccess) return true
            disconnect()
            return false
        }

        /**
         * Live audio shares the outbox to stay in order with the call's other events, but only up to
         * [LIVE_AUDIO_FRAMES]; past that it is dropped rather than closing the connection.
         */
        fun offerLiveVoice(output: LiveVoiceOutput): Delivery {
            if (output !is LiveVoiceOutput.Audio) return if (offer(frame(Api.LiveVoiceOutput, output))) Delivery.Queued else Delivery.Closed
            if (!valid()) { if (closeAfterResponse == null) disconnect(); return Delivery.Closed }
            if (liveAudio.incrementAndFetch() > LIVE_AUDIO_FRAMES) { liveAudio.decrementAndFetch(); return Delivery.Dropped }
            val sent = outbox.trySend(frame(Api.LiveVoiceOutput, output))
            if (sent.isSuccess) return Delivery.Queued
            liveAudio.decrementAndFetch()
            return if (sent.isClosed) Delivery.Closed else Delivery.Dropped
        }

        fun disconnect() {
            screenFrames.close()
            deviceId?.let { id -> scope.launch { screens.close(id) } }
            clients.remove(this)
            harness.uiCatalogs.disconnect(this)
            detachTools()
            outbox.close()
            // Called while the log may hold its lock, so unsubscribing happens after.
            scope.launch {
                harness.log.unsubscribeAll(this@Client)
                connection.close()
            }
        }
    }

    suspend fun serve(connection: FrameConnection) {
        val serving = scope.async(start = CoroutineStart.UNDISPATCHED) { serveConnection(connection) }
        try { serving.await() } finally { withContext(NonCancellable) { serving.cancelAndJoin() } }
    }

    fun rotateBrowserToken(token: String): String {
        val replacement = auth.rotate(token)
        clients.block { it.filter { c -> !c.valid() } }.forEach { it.disconnect() }
        return replacement
    }

    private suspend fun serveConnection(connection: FrameConnection) {
        val client = Client(connection)
        val requests = CoroutineScope(currentCoroutineContext() + SupervisorJob(currentCoroutineContext()[Job]))
        client.callScope = requests
        val outstanding = Semaphore(security.concurrentRequests)
        val voiceRequests = ConcurrentMutableMap<String, Job>()
        val timeout = requests.launch { delay(security.authenticationTimeoutMillis); client.disconnect() }
        val validity = requests.launch {
            while (isActive) { delay(security.validationIntervalMillis); if (!client.valid() && client.closeAfterResponse == null) client.disconnect() }
        }
        var window = TimeSource.Monotonic.markNow()
        var messages = 0
        var voiceInputs = 0
        var screenAcks = 0
        val writer = requests.launch { try { while (isActive) {
            val next = client.outbox.tryReceive().getOrNull()?.let { false to it } ?: select<Pair<Boolean, Frame?>> {
                client.outbox.onReceiveCatching { it.isClosed to it.getOrNull() }
                client.screenFrames.ready.onReceiveCatching { signal ->
                    signal.isClosed to if (signal.isClosed) null else client.screenFrames.poll()?.let { frame(Api.ScreenFrame, it) }
                }
            }
            val frame = next.second ?: if (next.first) break else continue
            if (frame is Frame.Event && frame.event == Api.LiveVoiceOutput.name && frame.payload.jsonObject["type"]?.jsonPrimitive?.content == "audio") client.liveAudio.decrementAndFetch()
            if (!client.valid() && (frame !is Frame.Response || frame.id != client.closeAfterResponse)) {
                if (client.closeAfterResponse != null) { continue }
                client.disconnect(); break
            }
            withTimeout(15_000) { connection.send(frame) }
            if (frame is Frame.Response && frame.id == client.closeAfterResponse) client.disconnect()
        } } catch (e: CancellationException) { throw e }
        catch (e: Exception) { log.debug(e) { "Closing a connection: write failed" } }
        finally { client.disconnect() } }
        var authed = false
        try {
            connection.incoming.collect { decoded ->
                if (window.elapsedNow().inWholeMilliseconds >= 60_000) { window = TimeSource.Monotonic.markNow(); messages = 0; screenAcks = 0; voiceInputs = 0 }
                if (decoded is Frame.Event && decoded.event == Api.LiveVoiceInput.name) {
                    if (!authed || !client.valid() || client.linked || ++voiceInputs > security.liveVoiceInputsPerMinute) { client.disconnect(); return@collect }
                    try { client.liveVoice?.accept(ProtocolJson.decodeFromJsonElement(Api.LiveVoiceInput.payload, decoded.payload)) }
                    catch (_: IllegalArgumentException) { client.disconnect() }
                    return@collect
                }
                if (authed && client.valid() && client.closeAfterResponse == null && decoded is Frame.Request && decoded.method == Api.ScreenAck.name) {
                    if (++screenAcks > security.screenAcksPerMinute) { client.disconnect(); return@collect }
                    val response = handle(decoded, client)
                    if (!response.ok && ++messages > security.messagesPerMinute) { client.disconnect(); return@collect }
                    client.outbox.send(response)
                    return@collect
                }
                if (++messages > security.messagesPerMinute) { log.warn { "Closing a connection: over ${security.messagesPerMinute} messages a minute" }; client.disconnect(); return@collect }
                if (authed && !client.valid()) { if (client.closeAfterResponse == null) client.disconnect(); return@collect }
                if (client.closeAfterResponse != null) return@collect
                if (decoded is Frame.Response && authed) { client.response(decoded); return@collect }
                val request = decoded as? Frame.Request ?: return@collect
                if (!authed && request.method != Api.Connect.name) { connection.close(); return@collect }
                if (request.method == Api.Connect.name) {
                    val response = respond(request, Api.Connect) { p ->
                        check(!authed) { "Already connected" }
                        ProtocolVersion.requireCompatible(p.protocolVersion)
                        val supplied = connection.credential ?: p.token
                        client.linked = supplied?.let { remoteLinks.exposure(it) != null } == true
                        val token = (if (client.linked) supplied else auth.authenticate(p.device, supplied, if (connection.credential == null) p.pairingCode else null))
                            ?: error(if (p.token != null) "This device was unpaired" else "Invalid or expired pairing code")
                        client.token = token
                        if (!client.linked) client.refreshTools(p.deviceTools, initial = true)
                        if (!client.linked && auth.management(token)) p.timeZone?.let { zone -> runCatching { harness.timeZone = TimeZone.of(zone) } }
                        ConnectResult(token, name, harness.usage.value, capabilities, !client.linked && auth.management(token), deviceId = client.deviceId)
                    }
                    if (!response.ok) client.closeAfterResponse = request.id
                    client.outbox.send(response)
                    if (response.ok && !authed) {
                        authed = true
                        timeout.cancel()
                        clients.add(client)
                        broadcast(Api.DevicesChanged, Unit)
                    }
                    return@collect
                }
                if (request.method == Api.CancelVoice.name) {
                    client.outbox.send(respond(request, Api.CancelVoice) { voiceRequests[it.requestId]?.cancelAndJoin() })
                    return@collect
                }
                val voice = request.method == Api.Transcribe.name || request.method == Api.Synthesize.name
                if (voice && request.id in voiceRequests) {
                    client.outbox.send(Frame.Response(request.id, false, error = "Voice request is already active"))
                    return@collect
                }
                val stateful = request.method in setOf(Api.LiveVoiceStart.name, Api.LiveVoiceEnd.name, Api.CatchUp.name, Api.Unsubscribe.name, Api.Send.name, Api.Stop.name, Api.Presence.name, Api.DeviceTools.name, Api.SurfaceCatalogs.name, Api.RevokeDevice.name, Api.RotateToken.name)
                if (!outstanding.tryAcquire()) { log.warn { "Closing a connection: over ${security.concurrentRequests} requests at once (${request.method})" }; client.disconnect(); return@collect }
                if (stateful) {
                    try { client.outbox.send(handle(request, client)) } finally { outstanding.release() }
                } else {
                    val job = requests.launch(start = CoroutineStart.LAZY) {
                        // A reply for a connection that closed meanwhile has nowhere to go.
                        try { client.outbox.send(handle(request, client)) } catch (_: ClosedSendChannelException) {}
                    }
                    if (voice) voiceRequests[request.id] = job
                    job.invokeOnCompletion { if (voice) voiceRequests.remove(request.id); outstanding.release() }
                    job.start()
                }
            }
        } finally {
            client.liveVoice?.close()
            clients.remove(client)
            client.detachTools()
            if (authed) broadcast(Api.DevicesChanged, Unit)
            client.outbox.close()
            requests.cancel()
            withContext(NonCancellable) { harness.log.unsubscribeAll(client); connection.close() }
        }
    }

    private suspend fun handle(r: Frame.Request, client: Client): Frame.Response {
        if (client.linked) return when (r.method) {
            Api.Presence.name -> respond(r, Api.Presence) { Unit }
            Api.RemoteExecution.name -> respond(r, Api.RemoteExecution) { remoteGateway.call(requireNotNull(client.token), it) }
            else -> Frame.Response(r.id, ok = false, error = "Execution link does not allow this operation")
        }
        if (!client.authenticated(auth::management) && r.method !in DEVICE_METHODS)
            return Frame.Response(r.id, ok = false, error = "This action requires management access")
        if (r.method == Api.LiveVoiceStart.name) return respond(r, Api.LiveVoiceStart) { p ->
            check(client.liveVoice?.active != true) { "A live voice call is already active on this connection" }
            val provider = checkNotNull(voice.providers.speechToSpeech) { "Live voice is unavailable or disabled on this Server" }
            val registration = executions.registration(p.agentId)
            val name = registration?.let { externalInfo(it).name } ?: agent(p.agentId).name
            sourceSnapshot(p.agentId, p.sessionId)
            LiveVoiceSession(provider, client.callScope, voiceContext(name, harness.store.entries(p.agentId, p.sessionId)),
                client::offerLiveVoice,
                askAgent = { task ->
                    if (registration != null) {
                        try {
                            return@LiveVoiceSession withTimeout(300_000) {
                                val before = harness.store.entries(p.agentId, p.sessionId).size
                                send(SendParams(agentId = p.agentId, sessionId = p.sessionId, text = task, voice = true))
                                var snapshot = sourceSnapshot(p.agentId, p.sessionId)
                                while (snapshot.phase == ExecutionPhase.Running || snapshot.phase == ExecutionPhase.RequiresAction) {
                                    delay(100)
                                    snapshot = sourceSnapshot(p.agentId, p.sessionId)
                                }
                                if (snapshot.phase == ExecutionPhase.Lost) "The Agent could not finish the request."
                                else harness.store.entries(p.agentId, p.sessionId).drop(before)
                                    .filterIsInstance<SessionEntry.AssistantMessage>().lastOrNull()?.text ?: "The Agent finished without a reply."
                            }
                        } catch (e: TimeoutCancellationException) { return@LiveVoiceSession "The Agent took too long to reply." }
                        catch (e: CancellationException) { throw e }
                        catch (e: Exception) { return@LiveVoiceSession "The Agent could not finish the request." }
                    }
                    runs.join(p.sessionId)
                    var reply = "The Agent finished without a reply."
                    var failure: String? = null
                    sendNative(SendParams(agentId = p.agentId, sessionId = p.sessionId, text = task, voice = true)) {
                        when (it) {
                            is AgentEvent.TextMessageEnd -> reply = it.text
                            is AgentEvent.RunError -> failure = "The Agent could not finish the request"
                            else -> Unit
                        }
                    }
                    runs.join(p.sessionId)
                    failure?.let { error(it) }
                    reply
                }, record = { entry -> harness.store.append(p.agentId, p.sessionId, entry) }, keepAwake = keepAwake).also { client.liveVoice = it }.start()
        }
        if (r.method == Api.LiveVoiceEnd.name) return respond(r, Api.LiveVoiceEnd) { if (client.liveVoice?.id == it.callId) client.liveVoice?.close() }
        return voice.handle(r) ?: knowledge.handle(r) ?: tasks.handle(r) ?: settings.handle(r) ?: when (r.method) {
        Api.Capabilities.name -> respond(r, Api.Capabilities) { p ->
            val registration = external.registrationOrArchived(p.agentId)
            if (registration == null) harness.capabilities(agent(p.agentId), p.sessionId, p.settings, capabilities, seq.load())
            else external.capabilities(p.agentId, p.sessionId, externalInfo(registration).readiness, capabilities, seq.load())
        }
        Api.ProbeHarnessConnection.name -> respond(r, Api.ProbeHarnessConnection) { checkHarness(it) }
        Api.ResumeExecution.name -> respond(r, Api.ResumeExecution) { executionManagement.withLock { external.resume(it.id) } }
        Api.CloseExecution.name -> respond(r, Api.CloseExecution) { executionManagement.withLock { external.closeExecution(it.id) } }
        Api.ExecutionSnapshot.name -> respond(r, Api.ExecutionSnapshot) { if (external.owns(it.id)) external.snapshot(it.id) else nativeExecution.snapshot(nativeOwner(it.id) ?: error("Unknown Session"), it.id) }
        Api.ExecutionAnswer.name -> respond(r, Api.ExecutionAnswer) { dispatchFor(it.sessionId).answer(it) }
        Api.GrantExecution.name -> respond(r, Api.GrantExecution) { p ->
            require(p.agentIds.all { id -> if (executions.registration(id) != null) executions.configured(id).connection !is ExecutionConnection.Remote else harness.store.agents().any { it.id == id } }) { "Only installed source execution Agents may be exposed" }
            remoteLinks.grant(p)
        }
        Api.ExecutionGrants.name -> respond(r, Api.ExecutionGrants) { remoteLinks.grants() }
        Api.RevokeExecution.name -> respond(r, Api.RevokeExecution) { remoteLinks.revoke(it.id) }
        Api.SaveRemoteLink.name -> respond(r, Api.SaveRemoteLink) { link -> executionManagement.withLock {
            val connections = executions.catalog().connections.filter { (it.config.connection as? ExecutionConnection.Remote)?.serverId == link.id }
            check(connections.none { external.inUse(it.config.id) }) { "Connection has live Sessions" }
            remoteLinks.save(link)
        } }
        Api.DiscoverRemote.name -> respond(r, Api.DiscoverRemote) { remoteFactory.discover(it.id) }
        Api.ExecutionCatalog.name -> respond(r, Api.ExecutionCatalog) { executions.catalog() }
        Api.SaveHarnessConnection.name -> respond(r, Api.SaveHarnessConnection) { executionManagement.withLock { check(!external.inUse(it.id)) { "Connection has live Sessions" }; executions.save(it).also { catalogChanged() } } }
        Api.DeleteHarnessConnection.name -> respond(r, Api.DeleteHarnessConnection) { executionManagement.withLock { check(!external.inUse(it.id)) { "Connection has live Sessions" }; executions.removeConnection(it.id).also { catalogChanged() } } }
        Api.RegisterExternalAgent.name -> respond(r, Api.RegisterExternalAgent) { executionManagement.withLock { val existing = executions.registration(it.id); check(existing == null || !external.agentInUse(existing.id)) { "Agent has live Sessions" }; require(harness.store.agents().none { a -> a.id == it.id }) { "Agent ID belongs to a native Agent" }; executions.register(it).also { a -> external.restore(a.id); catalogChanged() } } }
        Api.UnregisterExternalAgent.name -> respond(r, Api.UnregisterExternalAgent) { executionManagement.withLock { val a = executions.registration(it.id); check(a == null || !external.agentInUse(a.id)) { "Agent has live Sessions" }; external.archive(it.id); executions.removeAgent(it.id).also { catalogChanged() } } }
        Api.Agents.name -> respond(r, Api.Agents) { admin.agents() + externalAgents() }
        Api.Agent.name -> respond(r, Api.Agent) { external.registrationOrArchived(it.agentId)?.let { a -> AgentDetails(externalInfo(a), AgentSettings("", "", "", "external"), "", "", managed = true, cwd = a.cwd, remoteAgentId = a.remoteAgentId) } ?: admin.details(agent(it.agentId)) }
        Api.CreateAgent.name -> respond(r, Api.CreateAgent) { p -> require(executions.catalog().agents.none { it.id == p.agentId }) { "Agent ID is reserved by an external registration" }; admin.create(p) }
        Api.UpdateAgent.name -> respond(r, Api.UpdateAgent) { admin.update(it).also { changedCapabilities() } }
        Api.TestExec.name -> respond(r, Api.TestExec) { admin.testExec(it) }
        Api.ImageModel.name -> respond(r, Api.ImageModel) { admin.imageModel() }
        Api.SaveImageModel.name -> respond(r, Api.SaveImageModel) { admin.saveImageModel(it).also { changedCapabilities() } }
        Api.ServerRules.name -> respond(r, Api.ServerRules) { admin.serverRules() }
        Api.SaveServerRules.name -> respond(r, Api.SaveServerRules) { admin.saveServerRules(it).also { changedCapabilities() } }
        Api.DeleteAgent.name -> respond(r, Api.DeleteAgent) { admin.delete(it.agentId) }
        Api.Sessions.name -> respond(r, Api.Sessions) { harness.log.page(it.agentId, it.before, it.limit.coerceIn(1, MAX_PAGE), it.routineId) }
        Api.Session.name -> respond(r, Api.Session) { harness.log.session(it.agentId, it.sessionId) }
        Api.Skills.name -> respond(r, Api.Skills) { admin.skills(agent(it.agentId)) }
        Api.Skill.name -> respond(r, Api.Skill) { admin.skill(agent(it.agentId), it.name, it.scope) }
        Api.SaveSkill.name -> respond(r, Api.SaveSkill) { admin.saveSkill(it) }
        Api.DeleteSkill.name -> respond(r, Api.DeleteSkill) { admin.deleteSkill(it) }
        Api.CatchUp.name -> respond(r, Api.CatchUp) { harness.log.subscribe(it.agentId, it.sessionId, it.after, it.limit.coerceIn(1, MAX_PAGE), client) }
        Api.Unsubscribe.name -> respond(r, Api.Unsubscribe) { harness.log.unsubscribe(it.sessionId, client) }
        Api.History.name -> respond(r, Api.History) { harness.log.history(it.agentId, it.sessionId, it.before, it.limit.coerceIn(1, MAX_PAGE)) }
        Api.CreateSession.name -> respond(r, Api.CreateSession) { createSession(it.agentId) }
        Api.ConfigureSession.name -> respond(r, Api.ConfigureSession) { p ->
            val agent = agent(p.agentId)
            check(harness.store.entries(agent.id, p.sessionId).isNotEmpty()) { "No session '${p.sessionId}'" }
            harness.configure(agent, p.sessionId, p.settings).also { changedCapabilities() }
        }
        Api.UpdateSession.name -> respond(r, Api.UpdateSession) { p ->
            check(harness.store.entries(p.agentId, p.sessionId).isNotEmpty())
            harness.log.updateDetails(p.agentId, p.sessionId, p.title, p.favorite)
        }
        Api.UploadAttachment.name -> respond(r, Api.UploadAttachment) { withContext(Dispatchers.Default) { harness.attachments.upload(prepareAttachment(it)) } }
        Api.SurfaceCatalogs.name -> respond(r, Api.SurfaceCatalogs) { harness.uiCatalogs.connect(client, it.catalogs) }
        Api.SurfaceImage.name -> respond(r, Api.SurfaceImage) { fetchSurfaceImage(it.url) }
        Api.AppResource.name -> respond(r, Api.AppResource) { harness.appResource(it) }
        Api.AppCallTool.name -> respond(r, Api.AppCallTool) { harness.appCallTool(agent(it.app.agentId), it) }
        Api.Attachment.name -> respond(r, Api.Attachment) { withContext(Dispatchers.Default) { harness.attachments.download(it.id) } }
        Api.Send.name -> respond(r, Api.Send) { send(it, client) }
        Api.Stop.name -> respond(r, Api.Stop) { stop(it.sessionId) }
        Api.ReviewReflection.name -> respond(r, Api.ReviewReflection) { reflectionReview.respond(agent(it.agentId), it.sessionId, it.proposalId, it.approve, it.note) }
        Api.Approve.name -> respond(r, Api.Approve) { harness.approvals.respond(it.approvalId, it.decision) }
        Api.Answer.name -> respond(r, Api.Answer) { harness.questions.respond(it.questionsId, it.answers) }
        Api.Artifacts.name -> respond(r, Api.Artifacts) { harness.artifacts.list() }
        Api.Artifact.name -> respond(r, Api.Artifact) { harness.artifacts.get(it.id) }
        Api.SaveArtifact.name -> respond(r, Api.SaveArtifact) { harness.artifacts.replace(it.id, it.content) }
        Api.DeleteArtifact.name -> respond(r, Api.DeleteArtifact) { harness.artifacts.delete(it.id) }
        Api.Routines.name -> respond(r, Api.Routines) { harness.routines.list(agent(it.agentId)) }
        Api.CreateRoutine.name -> respond(r, Api.CreateRoutine) { p ->
            val agent = agent(p.agentId)
            harness.routines.create(agent, checkSession(agent, p.spec))
        }
        Api.UpdateRoutine.name -> respond(r, Api.UpdateRoutine) { p ->
            val agent = agent(p.agentId)
            val spec = checkSession(agent, p.spec)
            harness.routines.update(agent, p.routineId) { it.with(spec) } ?: error("No routine '${p.routineId}'")
        }
        Api.RunRoutine.name -> respond(r, Api.RunRoutine) { p ->
            check(scheduling) { "No server is running to run it; start one with `kinuvo serve` or the desktop app" }
            checkNotNull(harness.routines.update(agent(p.agentId), p.routineId) { it.copy(runRequested = true) }) { "No routine '${p.routineId}'" }
        }
        Api.RemoveRoutine.name -> respond(r, Api.RemoveRoutine) { p -> check(harness.routines.remove(agent(p.agentId), p.routineId)) { "No routine '${p.routineId}'" } }
        Api.Presence.name -> respond(r, Api.Presence) { client.watching = it.active; client.refreshTools(); if (foregroundOnly && unwatched()) stopWaiting(); changedCapabilities() }
        Api.DeviceTools.name -> respond(r, Api.DeviceTools) { client.refreshTools(it.tools); changedCapabilities(); broadcast(Api.DevicesChanged, Unit) }
        Api.SubscribePush.name -> respond(r, Api.SubscribePush) { p -> client.authenticated { auth.setPush(it, p.subscription) }; broadcast(Api.DevicesChanged, Unit) }
        Api.RotateToken.name -> respond(r, Api.RotateToken) {
            check(!client.browser) { "Rotate browser credentials over HTTPS" }
            val replacement = client.authenticated { auth.rotate(it).also { client.token = it } }
            clients.block { it.filter { c -> c.deviceId == client.deviceId && c !== client } }.forEach { it.disconnect() }
            replacement
        }
        Api.Pairing.name -> respond(r, Api.Pairing) { auth.pairing(it.refresh, it.management) ?: error("This Server can't pair Devices; pair with one started by `kinuvo serve` or the desktop app") }
        Api.PairedDevices.name -> respond(r, Api.PairedDevices) {
            val connected = clients.block { c -> c.mapNotNull { it.deviceId }.toSet() }
            client.authenticated(auth::paired).map { it.copy(connected = it.id in connected) }
        }
        Api.RevokeDevice.name -> respond(r, Api.RevokeDevice) { p ->
            check(client.authenticated(auth::management) || client.deviceId == p.id) { "This action requires management access" }
            if (client.deviceId == p.id) client.closeAfterResponse = r.id
            check(auth.revoke(p.id)) { "No paired Device '${p.id}'" }
            clients.block { it.filter { c -> c.deviceId == p.id && c !== client } }.forEach { it.disconnect() }
            if (client.deviceId == p.id) client.closeAfterResponse = r.id
            broadcast(Api.DevicesChanged, Unit)
        }
        Api.ScreenOpen.name -> respond(r, Api.ScreenOpen) { client.screens.open(requireNotNull(client.deviceId), it) }
        Api.Screens.name -> respond(r, Api.Screens) { client.screens.list(it) }
        Api.ScreenWatch.name -> respond(r, Api.ScreenWatch) { client.screens.watch(it) }
        Api.ScreenUnwatch.name -> respond(r, Api.ScreenUnwatch) { client.screens.unwatch(it); client.screenFrames.remove(it) }
        Api.ScreenAck.name -> respond(r, Api.ScreenAck) { client.screens.ack(it) }
        Api.ScreenControl.name -> respond(r, Api.ScreenControl) { client.screens.control(requireNotNull(client.deviceId), it) }
        Api.ScreenInput.name -> respond(r, Api.ScreenInput) { client.screens.input(requireNotNull(client.deviceId), it) }
        Api.ScreenCommand.name -> respond(r, Api.ScreenCommand) { client.screens.command(requireNotNull(client.deviceId), it) }
        Api.BrowserPresentation.name -> respond(r, Api.BrowserPresentation) { p ->
            browserOwner(p)
            requireNotNull(harness.browser).presentation(p)
        }
        Api.SaveBrowserPresentation.name -> respond(r, Api.SaveBrowserPresentation) { p ->
            browserOwner(p.owner)
            requireNotNull(harness.browser).presentation(p)
        }
        Api.BrowserTabs.name -> respond(r, Api.BrowserTabs) { p ->
            browserOwner(p)
            requireNotNull(harness.browser) { "Browser is unavailable on this Server" }.tabs(p)
        }
        Api.BrowserCommand.name -> respond(r, Api.BrowserCommand) { p ->
            browserOwner(p.owner)
            error("Use screens.control and frame-bound screens.input/command")
        }
        Api.Computers.name -> respond(r, Api.Computers) { harness.computers.list() }
        Api.WatchComputer.name -> respond(r, Api.WatchComputer) { error("Use bounded screens.watch") }
        Api.ComputerInput.name -> respond(r, Api.ComputerInput) { error("Use frame-bound screens.input") }
        Api.Takeover.name -> respond(r, Api.Takeover) { error("Use generation-bound screens.control") }
        else -> Frame.Response(r.id, ok = false, error = "Unknown method ${r.method}")
        }
    }

    private suspend fun execution(agentId: String): ExecutionDispatch =
        if (executions.registration(agentId) != null) external else nativeExecution

    private suspend fun createSession(agentId: String): SessionInfo =
        if (executions.registration(agentId) != null) executionManagement.withLock { execution(agentId).create(agentId) }
        else execution(agentId).create(agentId)

    private suspend fun sourceSnapshot(agentId: String, sessionId: String) = execution(agentId).snapshot(agentId, sessionId)

    /** Native and external Sessions each run through their own [ExecutionDispatch]; this picks the one that owns [sessionId]. */
    private suspend fun dispatchFor(sessionId: String): ExecutionDispatch = if (external.owns(sessionId)) external else nativeExecution

    private suspend fun nativeOwner(sessionId: String) = harness.store.agents().firstOrNull { a -> harness.store.sessions(a.id).any { it.id == sessionId } }?.id

    private suspend fun answerNative(answer: ExecutionAnswer): Boolean {
        val agentId = nativeOwner(answer.sessionId) ?: return false

        val pending = nativeExecution.snapshot(agentId, answer.sessionId).pending.firstOrNull { it.id == answer.requestId }
        return when (pending?.kind) {
            ExecutionRequestKind.Approval -> {
                val option = (answer.answer as? JsonObject)?.get("optionId")?.jsonPrimitive?.content
                require(option == null || pending.options.any { it.id == option })
                harness.approvals.respond(answer.requestId, when (option) {
                    "allow_once" -> ApprovalDecision.AllowOnce
                    "allow_session" -> ApprovalDecision.AllowForSession
                    "always_allow" -> ApprovalDecision.AlwaysAllow
                    else -> ApprovalDecision.Deny()
                })
            }
            ExecutionRequestKind.Question -> {
                val answers = (answer.answer as? JsonObject)?.get("answers")?.let {
                    ProtocolJson.decodeFromJsonElement(MapSerializer(serializer<String>(), ListSerializer(serializer<String>())), it)
                }.orEmpty()
                harness.questions.respond(answer.requestId, answers)
            }
            null -> false
        }
    }

    private suspend fun browserOwner(owner: BrowserOwner) {
        check(executions.registration(owner.agentId) == null) {
            "Browser is managed by this Agent's harness and isn't available in Kinuvo"
        }
        agent(owner.agentId)
    }

    private suspend fun send(p: SendParams, client: Client? = null): SendResult {
        val isExternal = executions.registration(p.agentId) != null
        val execution = execution(p.agentId)
        return if (isExternal) executionManagement.withLock { execution.send(p) } else sendNative(p, harness.uiCatalogs.forRun(client))
    }

    private suspend fun sendNative(p: SendParams, catalogs: List<UiCatalog> = harness.uiCatalogs.forRun()): SendResult = sendNative(p, catalogs) {}

    private suspend fun sendNative(p: SendParams, catalogs: List<UiCatalog> = harness.uiCatalogs.forRun(), onEvent: (AgentEvent) -> Unit): SendResult {
        val agent = agent(p.agentId)
        require(p.text.isNotBlank() || p.attachments.isNotEmpty()) { "Add a message or attachment" }
        check(p.attachments.isEmpty() || ServerFeature.Attachments in capabilities.features) { "Attachments are not available on this Server" }
        require(p.attachments.size <= MAX_ATTACHMENTS && p.attachments.distinct().size == p.attachments.size) { "Attach up to 4 different files" }
        val attachments = withContext(Dispatchers.Default) { p.attachments.map(harness.attachments::get) }
        require(attachments.sumOf { it.size } <= MAX_ATTACHMENT_TOTAL_BYTES) { "Attachments must total 20 MB or less" }
        val sessionId = p.sessionId?.also { id ->
            check(harness.store.sessions(agent.id).any { it.id == id }) { "No session '$id' for agent '${agent.id}'" }
        } ?: harness.newSession(agent).id
        val effective = harness.capabilities(agent, sessionId, null, capabilities)
        effective.chat.requireReady()
        attachments.forEach { attachment -> effective.attachment(attachment.mimeType).requireReady() }
        val (activation, message) = (if (ServerFeature.Skills in capabilities.features) harness.slashActivation(agent, p.text) else null) ?: (null to p.text)
        val run = runs.start(agent, sessionId, message, activation, attachments = attachments, voice = p.voice, action = p.action, onEvent = onEvent, onDone = { reflector.ran(agent, sessionId) }) { harness.runner(agent, sessionId, catalogs = catalogs) }
        return SendResult(sessionId, run.id, message, activation)
    }

    private suspend fun stop(sessionId: String) {
        dispatchFor(sessionId).stop(sessionId)
        harness.stopChildren(sessionId)
    }

    private suspend fun checkHarness(config: HarnessConnection): ExecutionProbe {
        val probe = try {
            withTimeoutOrNull(30_000) { installedFactory.probe(config) }
                ?: ExecutionProbe(ExecutionReadiness(true, false, "Harness check timed out", "unavailable"))
        } catch (e: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            ExecutionProbe(ExecutionReadiness(true, false, "Harness check timed out", "unavailable"))
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            ExecutionProbe(ExecutionReadiness(true, false, e.message ?: "Harness check failed", "unavailable"))
        }
        executionManagement.withLock {
            if (executions.catalog().connections.any { it.config == config }) {
                if (probe.readiness.online) executions.online(config.id)
                else executions.offline(config.id, probe.readiness.reason ?: "Unavailable", probe.readiness.reasonCode ?: "unavailable")
                catalogChanged()
            }
        }
        return probe
    }

    private suspend fun catalogChanged() {
        broadcast(Api.ExecutionCatalogChanged, executions.catalog())
        broadcast(Api.AgentsChanged, admin.agents() + externalAgents())
        changedCapabilities()
    }

    private suspend fun externalAgents(): List<AgentInfo> =
        (executions.catalog().agents + external.archives()).distinctBy { it.id }.map { externalInfo(it) }

    private suspend fun externalInfo(a: AgentRegistration): AgentInfo {
        val c = executions.catalog().connections.firstOrNull { it.config.id == a.connectionId }
        if (executions.registration(a.id) == null) return AgentInfo(a.id, a.name, "external", a.avatar, ExecutionKind.Acp, a.connectionId, readiness = ExecutionReadiness(true, false, "Agent registration archived", "archived"))
        requireNotNull(c)
        return AgentInfo(a.id, a.name, "external", a.avatar, if (c.config.connection is ExecutionConnection.Acp) ExecutionKind.Acp else ExecutionKind.Remote, a.connectionId, c.config.host ?: name, c.readiness)
    }

    private suspend fun agent(id: String): Agent = admin.agent(id)

    private suspend fun askExternal(scope: CallerScope, tool: Tool, args: JsonObject, verdict: Verdict.Ask): ApprovalDecision {
        val options = listOf(ExternalApprovalOption("allow", "Allow once", "allow_once")) +
            (if (verdict.trustKeys.isNotEmpty()) listOf(ExternalApprovalOption("always", "Always allow", "allow_always")) else emptyList()) +
            ExternalApprovalOption("deny", "Deny", "reject_once")
        val description = listOfNotNull(verdict.assessment.reason, args.takeIf { it.isNotEmpty() }?.toString()).joinToString(" · ").ifEmpty { null }
        return when (external.ask(requireNotNull(scope.sessionId), "Kinuvo: ${tool.displayName}", description, options)) {
            "allow" -> ApprovalDecision.AllowOnce
            "always" -> ApprovalDecision.AlwaysAllow
            else -> ApprovalDecision.Deny()
        }
    }

    private suspend fun checkSession(agent: Agent, spec: RoutineSpec) = spec.also {
        val id = it.session?.takeIf { s -> s != NEW_SESSIONS } ?: return@also
        check(harness.store.entries(agent.id, id).isNotEmpty()) { "No session '$id'" }
    }

    private fun <T> broadcast(type: EventType<T>, payload: T) {
        val text = frame(type, payload)
        val managementOnly = type.name in setOf(Api.ExecutionCatalogChanged.name, Api.ScreensChanged.name, Api.BrowserChanged.name, Api.ComputersChanged.name)
        clients.block { it.toList() }.filter { !managementOnly || it.authenticated(auth::management) }.forEach { it.offer(text) }
    }

    private fun <T> frame(type: EventType<T>, payload: T): Frame.Event = Frame.Event(type.name, ProtocolJson.encodeToJsonElement(type.payload, payload), seq.incrementAndFetch())

    companion object {
        private const val OUTBOX_SIZE = 1024
        // Five seconds of 40 ms packets, leaving most of the outbox to other events.
        private const val LIVE_AUDIO_FRAMES = 125
    }
}
