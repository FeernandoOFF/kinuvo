package kinuvo.server

import arrow.core.raise.Raise
import arrow.core.raise.context.raise
import kinuvo.server.devices.*
import kinuvo.server.tools.AgentManagement
import kinuvo.server.reflection.*
import kinuvo.server.screens.*
import co.touchlab.stately.collections.ConcurrentMutableMap
import co.touchlab.stately.collections.ConcurrentMutableSet
import co.touchlab.stately.concurrency.Lock
import co.touchlab.stately.concurrency.withLock
import io.github.oshai.kotlinlogging.KotlinLogging
import kinuvo.harness.ToolReports
import kinuvo.harness.Agent
import kinuvo.server.execution.*
import kinuvo.harness.*
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.serializer
import kotlinx.serialization.json.JsonObject
import kinuvo.protocol.*
import kinuvo.protocol.agent.*
import kinuvo.protocol.approval.*
import kinuvo.protocol.browser.*
import kinuvo.protocol.capability.*
import kinuvo.protocol.computer.*
import kinuvo.protocol.connection.*
import kinuvo.protocol.device.*
import kinuvo.protocol.execution.*
import kinuvo.protocol.routine.*
import kinuvo.protocol.push.PushSubscription
import kinuvo.protocol.screen.*
import kinuvo.protocol.session.*
import kinuvo.protocol.surface.*
import kinuvo.protocol.usage.*
import kinuvo.protocol.voice.*
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

/** Builds the harness for linked Servers; [readiness] reports each connection going online or offline. */
typealias RemoteHarnessFactory = (readiness: suspend (connectionId: String, online: Boolean, code: String) -> Unit) -> RemoteHarness

@OptIn(ExperimentalAtomicApi::class)
class ServerRuntime(
    val harness: HarnessRuntime,
    val auth: PeerRegistry,
    private val routes: Routes,
    private val name: String = "kinuvo",
    private val foregroundOnly: Boolean = false,
    private val security: ConnectionSecurity = ConnectionSecurity(),
    executionRegistry: ExecutionRegistry = ExecutionRegistry(),
    private val executionFactory: ExecutionFactory? = null,
    nativeFactory: ExecutionFactory = harness.native,
    private val executionStorage: ExecutionRecoveryStore = MemoryExecutionRecovery(),
    val remoteLinks: RemoteLinks = RemoteLinks(),
    remoteHarness: RemoteHarnessFactory? = null,
    private val prepareAttachment: (UploadAttachmentParams) -> UploadAttachmentParams = { it },
    /** Held while a live voice call runs, so the host keeps serving it in real time. */
    private val keepAwake: () -> AutoCloseable = { AutoCloseable {} },
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val executions = executionRegistry
    private val remoteFactory = remoteHarness?.invoke { id, online, code ->
        val before = executions.catalog().connections.firstOrNull { it.config.id == id }
        if (before != null) {
            if (online) executions.negotiated(id, ExecutionCapabilities(cancel = true, questions = true, approvals = true))
            else executions.offline(id, if (code == "access_revoked") "Remote access revoked" else "Remote Server offline", code)
            if (before != executions.catalog().connections.firstOrNull { it.config.id == id }) catalogChanged()
        }
    }
    private val installedFactory = object : ExecutionFactory {
        override suspend fun probe(config: HarnessConnection) = if (config.connection is ExecutionConnection.Remote) remoteFactory?.probe(config) ?: ExecutionProbe(ExecutionReadiness(true, false, "Remote Servers unavailable", "unsupported")) else executionFactory?.probe(config) ?: ExecutionProbe(ExecutionReadiness(true, false, "ACP unavailable", "unsupported"))
        override suspend fun open(config: HarnessConnection, registration: AgentRegistration, state: ExecutionState, broker: ExecutionBroker, scope: CoroutineScope) =
            if (config.connection is ExecutionConnection.Remote) (remoteFactory ?: throw ServerException(ExecutionError.NoRemoteServers)).open(config, registration, state, broker, scope) else requireNotNull(executionFactory).open(config, registration, state, broker, scope)
    }
    private val remoteGateway = RemoteGateway(remoteLinks,
        { execution.agents() },
        { id -> dispatch.create(AgentId(id)).let { dispatch.snapshot(AgentId(id), it.id) } },
        { agent, id -> dispatch.snapshot(AgentId(agent), SessionId(id)) },
        { agent, id, text, action -> dispatch.send(SendParams(AgentId(agent), SessionId(id), text, action = action)); Unit },
        { _, id -> dispatch.stop(SessionId(id)) },
        { _, answer -> dispatch.answer(answer) },
        { _, id -> if (external.owns(SessionId(id))) external.discard(SessionId(id)) else dispatch.stop(SessionId(id)) })
    private val executionManagement = Mutex()
    private val external = ExecutionCoordinator(executions, installedFactory, scope, harness.log, executionStorage,
        resolve = { harness.attachments.download(it) }, readinessChanged = { catalogChanged() })
    suspend fun persistExecutionAttachment(state: ExecutionState, data: AttachmentData): Attachment {
        external.currentStateOwner(state)
        require(attachmentMimeType(data.attachment.name) == data.attachment.mimeType) { "Output filename and MIME differ" }
        return harness.attachments.upload(UploadAttachmentParams(data.attachment.name, data.base64)).also {
            require(it.mimeType == data.attachment.mimeType) { "Stored output MIME differs" }
        }
    }
    private val screenBudget = ScreenBudget()
    private val clients = ConcurrentMutableSet<Client>()
    private val runs = Runs(harness.log, scope, nativeFactory)
    private val devicePush = devicePush(auth, harness.serverId)
    private val notifications = Notifications(harness, scope, runs::routine, { external.registrationOrArchived(AgentId(it))?.name }, devicePush)
    private val workflows: Workflows = Workflows(harness, runs, scope, { broadcast(Api.WorkflowsChanged, it) }, { broadcast(Api.WorkflowRunChanged, it) }, wakeScheduler = { scheduler.wakeUp() })
    private val scheduler: Scheduler = Scheduler(harness, runs, scope, onRan = { broadcast(Api.RoutineRan, it); notifications.routineRan(it) }, workflowTick = workflows::tick)
    private val admin = Admin(harness, runs) { broadcast(Api.AgentsChanged, it) }.also { harness.agentManagement = AgentManagement(harness, it) }
    private val reflectionReview = ReflectionReview(harness)
    private val logins = ProviderLogins(harness.modelProviders, scope) { broadcast(Api.ProvidersChanged, it); changedCapabilities() }
    private val reflector = Reflector(harness, scope, runs::isActive)
    private val dispatch = AgentExecution(executions, external,
        NativeExecution(harness, runs, scope, { admin.agent(it) }, { capabilities }, reflector::ran) { sessionId, text ->
            clients.block { it.toList() }.filter { it.voiceSessionId == sessionId }.forEach { it.liveVoice?.notify(text) }
        }, executionManagement)
    private val subAgents = SubAgents(harness, dispatch, scope, runs::join).also { harness.subAgents = it; dispatch.subAgents = it; runs.onBegin = { _, id -> it.begin(id) }; runs.onFinish = it::finish; runs.onCancel = it::cancelChildren }
    private val dispatcher = TaskDispatcher(harness, runs, scope)
    private val seq = AtomicLong(0)
    private val sessions = SessionService(harness, admin)
    private val routines = RoutineService(harness, admin, sessions) { scheduling }
    private val browserAccess = BrowserAccess(harness, executions, admin)
    private val voice = VoiceService(harness, ::changedCapabilities)
    private val execution: ExecutionService = ExecutionService(harness, admin, executions, external, dispatch, executionManagement, installedFactory, remoteFactory, remoteLinks, remoteGateway, name, { capabilities }, { seq.load() }) { catalog, agents ->
        broadcast(Api.ExecutionCatalogChanged, catalog)
        broadcast(Api.AgentsChanged, agents)
        changedCapabilities()
    }
    private val teams = Teams(harness, dispatch, scope, runs::join, execution::agents) { broadcast(Api.TeamChanged, it) }.also { teams -> harness.teams = teams; runs.onFinish = { agent, session, cancelled -> subAgents.finish(agent, session, cancelled); teams.finished(session, cancelled) } }
    private val services = ServerServices(harness, auth, admin, execution, dispatch, sessions, teams, routines, workflows, browserAccess, voice, reflectionReview, logins, notifications, prepareAttachment, ::changedCapabilities)
    private var scheduling = false
    private var started = false
    private var closed = false
    private val lifecycle = Mutex()

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
        harness.log.executionMetadata = { info -> if (external.owns(info.id)) info.copy(legacyOrder = external.record(info.id).legacyOrder) else info }
        harness.browser?.authorize { owner ->
            throwing<ServerError, Unit> {
                browserAccess.validate(owner)
                if ("browser" !in admin.agent(owner.agentId).config.tools) raise(BrowserError.Disabled)
            }
        }
        harness.log.onDelete = { a, id -> external.delete(a, id) }; harness.computers.browserService = { harness.browser }; harness.devices.paired = auth::toolDevices; harness.computers.paired = auth::toolDevices
        if (foregroundOnly) harness.log.onChange { if (it.status == SessionStatus.Waiting && unwatched()) scope.launch { dispatch.stop(it.id) } }
    }

    private fun unwatched() = clients.block { it.none { c -> c.watching } }

    suspend fun start(schedule: Boolean = false) = lifecycle.withLock {
        check(!closed) { "Server is closed" }
        check(!started) { "Server already started" }
        started = true
        try {
            (executionStorage as? ExecutionStorage)?.migrate(harness.log)?.forEach { log.error { it } }
            workflows.recover()
            external.reload()
            external.recover()
            scope.launch { harness.usage.filterNotNull().collect { broadcast(Api.Usage, it) } }
            harness.log.onChange { if (!it.hidden) broadcast(Api.SessionChanged, it) }
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
                    try { execution.checkHarness(connection.config) }
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

    private inner class Client(private val connection: FrameConnection) : SessionLog.Subscriber, Caller {
        override val subscriber: SessionLog.Subscriber get() = this
        override val management get() = authenticated(auth::management)
        override val surfaceCatalogs get() = harness.uiCatalogs.forRun(this)
        var liveVoice: LiveVoiceSession? = null
        var voiceSessionId: SessionId? = null
        var callScope: CoroutineScope = scope
        override val browser = connection.credential != null
        val outbox = Channel<Frame>(OUTBOX_SIZE)
        val liveAudio = AtomicInt(0)
        val screenFrames = ScreenFrameLane()
        override val screens = Screens(harness.computers, { harness.browser }, { owner -> throwing<ServerError, Unit> { browserAccess.validate(owner) } }, scope, { requireNotNull(deviceId) }, screenBudget,
            screenFrames::offer, { screenFrames.remove(it.subscription); offer(frame(Api.ScreenStatus, it)) })
        @Volatile override var watching = true
        @Volatile var token: String? = null
        private val credentialLock = Lock()
        override var linked = false
        fun valid() = credentialLock.withLock { token?.let { if (linked) remoteLinks.exposure(it) != null else auth.valid(it) } != false }
        fun <T> authenticated(block: (String) -> T): T = credentialLock.withLock { block(requireNotNull(token)) }
        override var deviceId: String? = null
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
                check(result.ok) { result.error?.message ?: "The Device couldn't do that" }
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

        override fun requireDevice() = requireNotNull(deviceId)

        override fun setSurfaceCatalogs(catalogs: List<UiCatalog>) = harness.uiCatalogs.connect(this, catalogs)

        override suspend fun presence(active: Boolean) {
            if (linked) return
            watching = active
            refreshTools()
            if (foregroundOnly && unwatched()) stopWaiting()
            changedCapabilities()
        }

        override suspend fun setDeviceTools(tools: Set<DeviceToolKind>) {
            refreshTools(tools)
            changedCapabilities()
            broadcast(Api.DevicesChanged, Unit)
        }

        override fun setPush(subscription: PushSubscription?) {
            authenticated { auth.setPush(it, subscription) }
            broadcast(Api.DevicesChanged, Unit)
        }

        override fun pairedDevices(): List<PairedDeviceInfo> {
            val connected = clients.block { c -> c.mapNotNull { it.deviceId }.toSet() }
            return authenticated(auth::paired).map { it.copy(connected = it.id in connected) }
        }

        context(_: Raise<AccessError>, _: Raise<DeviceError>)
        override fun revokeDevice(id: String, requestId: String) {
            if (!authenticated(auth::management) && deviceId != id) raise(AccessError.ManagementRequired)
            if (deviceId == id) closeAfterResponse = requestId
            if (!auth.revoke(id)) raise(DeviceError.NotFound(id))
            clients.block { it.filter { c -> c.deviceId == id && c !== this } }.forEach { it.disconnect() }
            if (deviceId == id) closeAfterResponse = requestId
            broadcast(Api.DevicesChanged, Unit)
        }

        context(_: Raise<DeviceError>)
        override fun rotateToken(): String {
            if (browser) raise(DeviceError.BrowserCredentials)
            val replacement = authenticated { auth.rotate(it).also { replacement -> token = replacement } }
            clients.block { it.filter { c -> c.deviceId == deviceId && c !== this } }.forEach { it.disconnect() }
            return replacement
        }

        override fun unwatchScreen(id: String) = screenFrames.remove(id)

        context(_: Raise<VoiceError>, _: Raise<AgentError>)
        override suspend fun startLiveVoice(params: LiveVoiceParams) = startLiveVoice(this, params)

        override fun endLiveVoice(callId: String) { if (liveVoice?.id == callId) liveVoice?.close() }

        context(_: Raise<ExecutionError>)
        override suspend fun remoteExecution(call: RemoteExecutionCall) = execution.remoteExecution(requireNotNull(token), call)

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
                        if (authed) raise(ConnectError.AlreadyConnected)
                        if (!ProtocolVersion.isCompatible(p.protocolVersion)) raise(ConnectError.ProtocolMismatch)
                        val supplied = connection.credential ?: p.token
                        client.linked = supplied?.let { remoteLinks.exposure(it) != null } == true
                        val token = (if (client.linked) supplied else auth.authenticate(p.device, supplied, if (connection.credential == null) p.pairingCode else null))
                            ?: raise(if (p.token != null) ConnectError.Unpaired else ConnectError.InvalidPairing)
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
                    client.outbox.send(request.failure(VoiceError.RequestActive))
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
        val access = routes.access(r.method)
        if (client.linked) {
            if (access?.linked != true) return r.failure(AccessError.LinkRestricted)
        } else {
            if (access?.management != false && !client.authenticated(auth::management)) return r.failure(AccessError.ManagementRequired)
            if (access == null || access == RouteAccess.Linked) return r.failure(AccessError.UnknownMethod(r.method))
        }
        return routes.dispatch(Call(services, client, r.id), r)
    }

    context(_: Raise<VoiceError>, _: Raise<AgentError>)
    private suspend fun startLiveVoice(client: Client, p: LiveVoiceParams): LiveVoiceCall {
        if (client.liveVoice?.active == true) raise(VoiceError.CallActive)
        val provider = voice.providers.speechToSpeech ?: raise(VoiceError.NoLiveVoice)
        val registration = executions.registration(p.agentId)
        val name = registration?.let { execution.externalInfo(it).name } ?: admin.agent(p.agentId).name
        dispatch.snapshot(p.agentId, p.sessionId)
        client.voiceSessionId = p.sessionId
        return LiveVoiceSession(provider, client.callScope, voiceContext(name, harness.store.entries(p.agentId, p.sessionId)),
            client::offerLiveVoice,
            askAgent = { task, progress ->
                if (registration != null) {
                    try {
                        return@LiveVoiceSession withTimeout(300_000) {
                            val before = harness.store.entries(p.agentId, p.sessionId).size
                            val updates = VoiceAgentProgress(dispatch.snapshot(p.agentId, p.sessionId))
                            dispatch.send(SendParams(agentId = p.agentId, sessionId = p.sessionId, text = task, voice = true))
                            var snapshot = dispatch.snapshot(p.agentId, p.sessionId)
                            updates.update(snapshot).forEach { progress(it) }
                            while (snapshot.phase == ExecutionPhase.Running || snapshot.phase == ExecutionPhase.RequiresAction) {
                                delay(100)
                                snapshot = dispatch.snapshot(p.agentId, p.sessionId)
                                updates.update(snapshot).forEach { progress(it) }
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
                val updates = NativeVoiceProgress()
                throwing<ServerError, SendResult> {
                    dispatch.send(SendParams(agentId = p.agentId, sessionId = p.sessionId, text = task, voice = true), null) {
                        updates.update(it).forEach { update -> progress(update) }
                        when (it) {
                            is AgentEvent.TextMessageEnd -> reply = it.text
                            is AgentEvent.RunError -> failure = "The Agent could not finish the request"
                            else -> Unit
                        }
                    }
                }
                runs.join(p.sessionId)
                failure?.let { error(it) }
                reply
            }, record = { entry -> harness.store.append(p.agentId, p.sessionId, entry) }, keepAwake = keepAwake).also { client.liveVoice = it }.start()
    }

    private suspend fun catalogChanged() { execution.catalogChanged() }

    private fun <T> broadcast(type: EventType<T>, payload: T) {
        val text = frame(type, payload)
        val managementOnly = type.name in setOf(Api.ExecutionCatalogChanged.name, Api.ScreensChanged.name, Api.BrowserChanged.name, Api.ComputersChanged.name)
        clients.block { it.toList() }.filter { !managementOnly || it.authenticated(auth::management) }.forEach { it.offer(text) }
    }

    private fun <T> frame(type: EventType<T>, payload: T): Frame.Event = Frame.Event(type.name, ProtocolJson.encodeToJsonElement(type.payload, payload), seq.incrementAndFetch())

    companion object {
        /** Methods the runtime answers itself, before any route: the handshake and cancelling a voice request on this connection. */
        val handledMethods = setOf(Api.Connect.name, Api.CancelVoice.name)

        private const val OUTBOX_SIZE = 1024
        // Five seconds of 40 ms packets, leaving most of the outbox to other events.
        private const val LIVE_AUDIO_FRAMES = 125
    }
}
