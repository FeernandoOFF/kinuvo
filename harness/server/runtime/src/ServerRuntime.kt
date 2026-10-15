package kinuvo.harness.server

import co.touchlab.stately.collections.ConcurrentMutableMap
import co.touchlab.stately.collections.ConcurrentMutableSet
import co.touchlab.stately.concurrency.withLock
import co.touchlab.stately.concurrency.Lock
import kinuvo.harness.core.Agent
import kinuvo.harness.core.routines.NEW_SESSIONS
import kinuvo.harness.core.routines.with
import kinuvo.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.TimeSource
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.datetime.TimeZone
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi

@OptIn(ExperimentalAtomicApi::class)
class ServerRuntime(
    val harness: HarnessRuntime,
    val auth: PeerRegistry,
    private val name: String = "kinuvo",
    private val foregroundOnly: Boolean = false,
    private val security: ConnectionSecurity = ConnectionSecurity(),
    private val prepareAttachment: (UploadAttachmentParams) -> UploadAttachmentParams = { it },
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val clients = ConcurrentMutableSet<Client>()
    private val runs = Runs(harness.log, scope)
    private val notifications = Notifications(harness, scope, runs::routine)
    private val scheduler = Scheduler(harness, runs, scope, onRan = { broadcast(Api.RoutineRan, it); notifications.routineRan(it) })
    private val admin = Admin(harness, runs) { broadcast(Api.AgentsChanged, it) }
    private val reflectionReview = ReflectionReview(harness)
    private val logins = ProviderLogins(harness.modelProviders, scope) { broadcast(Api.ProvidersChanged, it); changedCapabilities() }
    private val reflector = Reflector(harness, scope, runs::isActive)
    private val dispatcher = TaskDispatcher(harness, runs, scope)
    private val seq = AtomicLong(0)
    private var scheduling = false
    private var started = false
    private var closed = false
    private val lifecycle = Mutex()

    val capabilities get() = ServerCapabilities(harness.serverId, harness.features + if (auth.pairable) setOf(ServerFeature.Pairing) else emptySet(),
        if (foregroundOnly) ExecutionLifetime.Foreground else ExecutionLifetime.Continuous,
        if (scheduling) WakeSupport.Scheduled else WakeSupport.None, integrations = harness.integrations.mapKeys { it.key.name }, platform = harness.platform, voice = harness.voice.setup)
    /** Runs going and the events they've produced; a foreground-only host keeps them alive while it can. */
    val runActivity get() = runs.activity

    /** With nobody watching, runs paused on an Approval or a question can't go on, so they stop, now or once they pause; the rest keep going. */
    private suspend fun stopWaiting() = runs.sessions().filter { harness.log.status(it) == SessionStatus.Waiting }.forEach(runs::stop)

    private fun changedCapabilities() = broadcast(Api.CapabilitiesChanged, Unit)
    init {
        harness.devices.paired = auth::toolDevices; harness.computers.paired = auth::toolDevices
        if (foregroundOnly) harness.log.onChange { if (it.status == SessionStatus.Waiting && unwatched()) runs.stop(it.id) }
    }

    private fun unwatched() = clients.block { it.none { c -> c.watching } }

    suspend fun start(schedule: Boolean = false) = lifecycle.withLock {
        check(!closed) { "Server is closed" }
        check(!started) { "Server already started" }
        started = true
        try {
            scope.launch { harness.usage.filterNotNull().collect { broadcast(Api.Usage, it) } }
            harness.log.onChange { broadcast(Api.SessionChanged, it) }
            harness.log.onEvent(notifications::on)
            harness.mcp.onSignInNeeded(notifications::mcpSignInNeeded)
            harness.routines.onChange { agent, routines -> broadcast(Api.RoutinesChanged, RoutinesChanged(agent.id, routines)) }
            harness.knowledge.onChange { broadcast(Api.KnowledgeChanged, it) }
            harness.tasks.onChange { broadcast(Api.TaskChanged, it) }
            harness.artifacts.onChange { broadcast(Api.ArtifactChanged, it) }
            harness.computers.onChange = { broadcast(Api.ComputersChanged, it) }
            if (schedule) { scheduler.start(); dispatcher.start() }
            scheduling = schedule
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
            runs.stopAll()
            clients.block { it.toList() }.forEach { it.disconnect() }
        } finally {
            try { scope.coroutineContext[Job]?.cancelAndJoin() } finally { harness.close() }
        }
    }

    private inner class Client(private val connection: FrameConnection) : SessionLog.Subscriber {
        val browser = connection.credential != null
        val outbox = Channel<Frame>(OUTBOX_SIZE)
        @kotlin.concurrent.Volatile override var watching = true
        @kotlin.concurrent.Volatile var token: String? = null
        private val credentialLock = Lock()
        fun valid() = credentialLock.withLock { token?.let(auth::valid) != false }
        fun <T> authenticated(block: (String) -> T): T = credentialLock.withLock { block(requireNotNull(token)) }
        var deviceId: String? = null
        var tools: Set<DeviceToolKind> = emptySet()
        @kotlin.concurrent.Volatile var closeAfterResponse: String? = null
        private val pending = ConcurrentMutableMap<String, CompletableDeferred<Frame.Response>>()
        val readDevice: suspend (DeviceToolRequest) -> DeviceToolResult = { p ->
            val id = kotlin.uuid.Uuid.random().toString()
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
            val id = kotlin.uuid.Uuid.random().toString()
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
            if (!valid()) { if (closeAfterResponse == null) disconnect(); return false }
            if (outbox.trySend(text).isSuccess) return true
            disconnect()
            return false
        }

        fun disconnect() {
            clients.remove(this)
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
        val outstanding = Semaphore(security.concurrentRequests)
        val timeout = requests.launch { delay(security.authenticationTimeoutMillis); client.disconnect() }
        val validity = requests.launch {
            while (isActive) { delay(security.validationIntervalMillis); if (!client.valid()) client.disconnect() }
        }
        var window = TimeSource.Monotonic.markNow()
        var messages = 0
        val writer = requests.launch { for (frame in client.outbox) {
            if (!client.valid() && (frame !is Frame.Response || frame.id != client.closeAfterResponse)) { client.disconnect(); break }
            connection.send(frame)
            if (frame is Frame.Response && frame.id == client.closeAfterResponse) client.disconnect()
        } }
        var authed = false
        try {
            connection.incoming.collect { decoded ->
                if (window.elapsedNow().inWholeMilliseconds >= 60_000) { window = TimeSource.Monotonic.markNow(); messages = 0 }
                if (++messages > security.messagesPerMinute) { client.disconnect(); return@collect }
                if (authed && !client.valid()) { client.disconnect(); return@collect }
                if (client.closeAfterResponse != null) return@collect
                if (decoded is Frame.Response && authed) { client.response(decoded); return@collect }
                val request = decoded as? Frame.Request ?: return@collect
                if (!authed && request.method != Api.Connect.name) { connection.close(); return@collect }
                if (request.method == Api.Connect.name) {
                    val response = respond(request, Api.Connect) { p ->
                        check(!authed) { "Already connected" }
                        val token = auth.authenticate(p.device, connection.credential ?: p.token, if (connection.credential == null) p.pairingCode else null)
                            ?: error(if (p.token != null) "This device was unpaired" else "Invalid or expired pairing code")
                        client.token = token
                        client.refreshTools(p.deviceTools, initial = true)
                        if (auth.management(token)) p.timeZone?.let { zone -> runCatching { harness.timeZone = TimeZone.of(zone) } }
                        ConnectResult(token, name, harness.usage.value, capabilities, auth.management(token))
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
                val stateful = request.method in setOf(Api.CatchUp.name, Api.Unsubscribe.name, Api.Send.name, Api.Stop.name, Api.Presence.name, Api.DeviceTools.name, Api.RevokeDevice.name, Api.RotateToken.name)
                if (!outstanding.tryAcquire()) { client.disconnect(); return@collect }
                if (stateful) {
                    try { client.outbox.send(handle(request, client)) } finally { outstanding.release() }
                } else requests.launch {
                    try { client.outbox.send(handle(request, client)) } finally { outstanding.release() }
                }
            }
        } finally {
            clients.remove(client)
            client.detachTools()
            if (authed) broadcast(Api.DevicesChanged, Unit)
            client.outbox.close()
            requests.cancel()
            withContext(NonCancellable) { harness.log.unsubscribeAll(client); connection.close() }
        }
    }

    private suspend fun handle(r: Frame.Request, client: Client): Frame.Response {
        if (!client.authenticated(auth::management) && r.method !in DEVICE_METHODS)
            return Frame.Response(r.id, ok = false, error = "This action requires management access")
        return when (r.method) {
        Api.Capabilities.name -> respond(r, Api.Capabilities) { harness.capabilities(agent(it.agentId), it.sessionId, it.settings, capabilities, seq.load()) }
        Api.Models.name -> respond(r, Api.Models) { harness.modelCatalog(it.refresh) }
        Api.Agents.name -> respond(r, Api.Agents) { admin.agents() }
        Api.Agent.name -> respond(r, Api.Agent) { admin.details(agent(it.agentId)) }
        Api.CreateAgent.name -> respond(r, Api.CreateAgent) { admin.create(it) }
        Api.UpdateAgent.name -> respond(r, Api.UpdateAgent) { admin.update(it).also { changedCapabilities() } }
        Api.TestExec.name -> respond(r, Api.TestExec) { admin.testExec(it) }
        Api.ServerRules.name -> respond(r, Api.ServerRules) { admin.serverRules() }
        Api.SaveServerRules.name -> respond(r, Api.SaveServerRules) { admin.saveServerRules(it).also { changedCapabilities() } }
        Api.DeleteAgent.name -> respond(r, Api.DeleteAgent) { admin.delete(it.agentId) }
        Api.Sessions.name -> respond(r, Api.Sessions) { harness.log.page(agent(it.agentId), it.before, it.limit.coerceIn(1, MAX_PAGE), it.routineId) }
        Api.Session.name -> respond(r, Api.Session) {
            val agent = agent(it.agentId)
            check(harness.store.entries(agent, it.sessionId).isNotEmpty()) { "No session '${it.sessionId}'" }
            harness.log.session(agent, it.sessionId)
        }
        Api.Skills.name -> respond(r, Api.Skills) { admin.skills(agent(it.agentId)) }
        Api.Skill.name -> respond(r, Api.Skill) { admin.skill(agent(it.agentId), it.name, it.scope) }
        Api.SaveSkill.name -> respond(r, Api.SaveSkill) { admin.saveSkill(it) }
        Api.DeleteSkill.name -> respond(r, Api.DeleteSkill) { admin.deleteSkill(it) }
        Api.McpServers.name -> respond(r, Api.McpServers) { harness.mcp.mcpServers(agent(it.agentId)) }
        Api.SaveMcp.name -> respond(r, Api.SaveMcp) { harness.mcp.saveMcp(it) }
        Api.RemoveMcp.name -> respond(r, Api.RemoveMcp) { harness.mcp.removeMcp(it) }
        Api.TestMcp.name -> respond(r, Api.TestMcp) { harness.mcp.testMcp(it) }
        Api.LoginMcp.name -> respond(r, Api.LoginMcp) { harness.mcp.loginMcp(it) }
        Api.CompleteMcpLogin.name -> respond(r, Api.CompleteMcpLogin) { harness.mcp.completeMcpLogin(it) }
        Api.LogoutMcp.name -> respond(r, Api.LogoutMcp) { harness.mcp.logoutMcp(it) }
        Api.CatchUp.name -> respond(r, Api.CatchUp) { harness.log.subscribe(agent(it.agentId), it.sessionId, it.after, it.limit.coerceIn(1, MAX_PAGE), client) }
        Api.Unsubscribe.name -> respond(r, Api.Unsubscribe) { harness.log.unsubscribe(it.sessionId, client) }
        Api.History.name -> respond(r, Api.History) { harness.log.history(agent(it.agentId), it.sessionId, it.before, it.limit.coerceIn(1, MAX_PAGE)) }
        Api.CreateSession.name -> respond(r, Api.CreateSession) { harness.newSession(agent(it.agentId)) }
        Api.ConfigureSession.name -> respond(r, Api.ConfigureSession) { p ->
            val agent = agent(p.agentId)
            check(harness.store.entries(agent, p.sessionId).isNotEmpty()) { "No session '${p.sessionId}'" }
            harness.configure(agent, p.sessionId, p.settings).also { changedCapabilities() }
        }
        Api.UpdateSession.name -> respond(r, Api.UpdateSession) { p ->
            val agent = agent(p.agentId)
            check(harness.store.entries(agent, p.sessionId).isNotEmpty()) { "No session '${p.sessionId}'" }
            harness.update(agent, p.sessionId, p.title, p.favorite)
        }
        Api.UploadAttachment.name -> respond(r, Api.UploadAttachment) { withContext(Dispatchers.Default) { harness.attachments.upload(prepareAttachment(it)) } }
        Api.Attachment.name -> respond(r, Api.Attachment) { withContext(Dispatchers.Default) { harness.attachments.download(it.id) } }
        Api.Send.name -> respond(r, Api.Send) { send(it) }
        Api.Stop.name -> respond(r, Api.Stop) { stop(it.sessionId) }
        Api.ReviewReflection.name -> respond(r, Api.ReviewReflection) { reflectionReview.respond(agent(it.agentId), it.sessionId, it.proposalId, it.approve) }
        Api.Approve.name -> respond(r, Api.Approve) { harness.approvals.respond(it.approvalId, it.decision) }
        Api.Answer.name -> respond(r, Api.Answer) { harness.questions.respond(it.questionsId, it.answers) }
        Api.Artifacts.name -> respond(r, Api.Artifacts) { harness.artifacts.list() }
        Api.Artifact.name -> respond(r, Api.Artifact) { harness.artifacts.get(it.id) }
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
        Api.KnowledgeSearch.name -> respond(r, Api.KnowledgeSearch) { harness.knowledge.search(it.query, it.type, it.limit.coerceIn(1, MAX_PAGE)) }
        Api.KnowledgeNote.name -> respond(r, Api.KnowledgeNote) { harness.knowledge.find(it.ref) ?: error("No note '${it.ref}'") }
        Api.KnowledgeChanges.name -> respond(r, Api.KnowledgeChanges) { harness.knowledge.changes(it.limit.coerceIn(1, MAX_PAGE)) }
        Api.UndoKnowledgeChange.name -> respond(r, Api.UndoKnowledgeChange) { harness.knowledge.undo(it.changeId) }
        Api.TaskLists.name -> respond(r, Api.TaskLists) { harness.tasks.lists() }
        Api.Tasks.name -> respond(r, Api.Tasks) { harness.tasks.all(it.includeDone) }
        Api.CreateTask.name -> respond(r, Api.CreateTask) { p ->
            harness.tasks.create(p.title, p.list, p.notes, p.due?.let(kotlinx.datetime.LocalDate::parse), p.assignee)
        }
        Api.UpdateTask.name -> respond(r, Api.UpdateTask) { p -> harness.tasks.update(p.taskId, p.title, p.status, p.assignee, p.due) }
        Api.TasksSettings.name -> respond(r, Api.TasksSettings) { harness.tasksSetup() }
        Api.SaveTasksSettings.name -> respond(r, Api.SaveTasksSettings) { harness.saveTasksSettings(it) }
        Api.ProviderLists.name -> respond(r, Api.ProviderLists) { harness.tasks.providerLists(it.provider) }
        Api.AuthorizeTasks.name -> respond(r, Api.AuthorizeTasks) { harness.tasks.authorize(it.provider) }
        Api.KnowledgeSettings.name -> respond(r, Api.KnowledgeSettings) { harness.knowledgeSetup() }
        Api.SaveKnowledgeSettings.name -> respond(r, Api.SaveKnowledgeSettings) { harness.saveKnowledgeSettings(it) }
        Api.TestKnowledge.name -> respond(r, Api.TestKnowledge) { harness.testKnowledge(it) }
        Api.SecretProviders.name -> respond(r, Api.SecretProviders) { harness.secrets.providers.map { it.status() } }
        Api.ConnectSecrets.name -> respond(r, Api.ConnectSecrets) { p ->
            harness.secrets.provider(p.scheme).run { connect(p.token); harness.secrets.refresh(); status() }
        }
        Api.DisconnectSecrets.name -> respond(r, Api.DisconnectSecrets) { p ->
            harness.secrets.provider(p.scheme).run { disconnect(); harness.secrets.refresh(); status() }
        }
        Api.BrowseSecrets.name -> respond(r, Api.BrowseSecrets) { harness.secrets.provider(it.scheme).browse(it.path) }
        Api.RefreshSecrets.name -> respond(r, Api.RefreshSecrets) { harness.secrets.refresh() }
        Api.Presence.name -> respond(r, Api.Presence) { client.watching = it.active; client.refreshTools(); if (foregroundOnly && unwatched()) stopWaiting(); changedCapabilities() }
        Api.DeviceTools.name -> respond(r, Api.DeviceTools) { client.refreshTools(it.tools); changedCapabilities(); broadcast(Api.DevicesChanged, Unit) }
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
            check(auth.revoke(p.id)) { "No paired Device '${p.id}'" }
            clients.block { it.filter { c -> c.deviceId == p.id && c !== client } }.forEach { it.disconnect() }
            if (client.deviceId == p.id) client.closeAfterResponse = r.id
            broadcast(Api.DevicesChanged, Unit)
        }
        Api.Computers.name -> respond(r, Api.Computers) { harness.computers.list() }
        Api.WatchComputer.name -> respond(r, Api.WatchComputer) { if (it.watch) harness.computers.watch(it.computerId, client.watcher) else harness.computers.unwatch(it.computerId, client.watcher) }
        Api.ComputerInput.name -> respond(r, Api.ComputerInput) { harness.computers.input(it.computerId, it.action) }
        Api.Takeover.name -> respond(r, Api.Takeover) { harness.computers.takeover(it.computerId, it.take) }
        Api.Providers.name -> respond(r, Api.Providers) { logins.list() }
        Api.SetProviderApiKey.name -> respond(r, Api.SetProviderApiKey) { logins.saveApiKey(it.provider, it.value) }
        Api.LoginProvider.name -> respond(r, Api.LoginProvider) { logins.login(it.provider) }
        Api.LogoutProvider.name -> respond(r, Api.LogoutProvider) { logins.logout(it.provider) }
        Api.Service.name -> respond(r, Api.Service) { harness.serviceSetup(it.kind) }
        Api.SaveService.name -> respond(r, Api.SaveService) { harness.saveService(it.kind, it.settings, it.secretUpdates) }
        Api.TestService.name -> respond(r, Api.TestService) { harness.testService(it) }
        else -> Frame.Response(r.id, ok = false, error = "Unknown method ${r.method}")
        }
    }

    private suspend fun send(p: SendParams): SendResult {
        val agent = agent(p.agentId)
        require(p.text.isNotBlank() || p.attachments.isNotEmpty()) { "Add a message or attachment" }
        check(p.attachments.isEmpty() || ServerFeature.Attachments in capabilities.features) { "Attachments are not available on this Server" }
        require(p.attachments.size <= MAX_ATTACHMENTS && p.attachments.distinct().size == p.attachments.size) { "Attach up to 4 different files" }
        val attachments = withContext(Dispatchers.Default) { p.attachments.map(harness.attachments::get) }
        require(attachments.sumOf { it.size } <= MAX_ATTACHMENT_TOTAL_BYTES) { "Attachments must total 20 MB or less" }
        val sessionId = p.sessionId?.also { id ->
            check(harness.store.sessions(agent).any { it.id == id }) { "No session '$id' for agent '${agent.id}'" }
        } ?: harness.newSession(agent).id
        val effective = harness.capabilities(agent, sessionId, null, capabilities)
        effective.chat.requireReady()
        attachments.forEach { attachment -> effective.input(when {
            attachment.mimeType.startsWith("image/") -> "image"
            attachment.mimeType.startsWith("text/") || attachment.mimeType == "application/json" -> "text"
            else -> "file"
        }).requireReady() }
        val (activation, message) = (if (ServerFeature.Skills in capabilities.features) harness.slashActivation(agent, p.text) else null) ?: (null to p.text)
        val run = runs.start(agent, sessionId, message, activation, attachments = attachments, voice = p.voice, onDone = { reflector.ran(agent, sessionId) }) { harness.runner(agent, sessionId) }
        return SendResult(sessionId, run.id, message, activation)
    }

    private fun stop(sessionId: String) = runs.stop(sessionId)

    private suspend fun agent(id: String): Agent = admin.agent(id)

    private suspend fun checkSession(agent: Agent, spec: RoutineSpec) = spec.also {
        val id = it.session?.takeIf { s -> s != NEW_SESSIONS } ?: return@also
        check(harness.store.entries(agent, id).isNotEmpty()) { "No session '$id'" }
    }

    private fun <T> broadcast(type: EventType<T>, payload: T) {
        val text = frame(type, payload)
        clients.block { it.toList() }.forEach { it.offer(text) }
    }

    private fun <T> frame(type: EventType<T>, payload: T): Frame = Frame.Event(type.name, ProtocolJson.encodeToJsonElement(type.payload, payload), seq.incrementAndFetch())

    private suspend fun <P, R> respond(r: Frame.Request, method: Method<P, R>, block: suspend (P) -> R): Frame.Response =
        try {
            val params = ProtocolJson.decodeFromJsonElement(method.params, r.params)
            Frame.Response(r.id, ok = true, payload = encode(method.result, block(params)))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Frame.Response(r.id, ok = false, error = e.message ?: e.toString())
        }

    private fun <R> encode(serializer: KSerializer<R>, value: R): JsonElement = ProtocolJson.encodeToJsonElement(serializer, value)

    companion object {
        private const val MAX_PAGE = 200
        private const val OUTBOX_SIZE = 1024
    }
}
