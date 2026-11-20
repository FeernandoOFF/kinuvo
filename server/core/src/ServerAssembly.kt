package kinuvo.server

import arrow.core.raise.context.raise
import kinuvo.server.devices.*
import kinuvo.server.tools.AgentManagement
import kinuvo.server.reflection.*
import kinuvo.server.screens.*
import kinuvo.server.execution.*
import kinuvo.harness.*
import kinuvo.protocol.*
import kinuvo.protocol.agent.*
import kinuvo.protocol.browser.*
import kinuvo.protocol.capability.*
import kinuvo.protocol.execution.*
import kinuvo.protocol.session.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.sync.Mutex

/** What the services need from the [ServerRuntime] they were built for: its connections and what it is doing. */
internal interface ServerHub {
    val capabilities: ServerCapabilities
    val scheduling: Boolean
    val sequence: Long
    fun <T> broadcast(type: EventType<T>, payload: T)
    fun changedCapabilities()
    suspend fun notifyVoice(sessionId: SessionId, text: String)
}

/**
 * Builds the services of one Server and wires them to each other and to [harness]. The wiring has cycles (the services
 * need the harness, and the harness's tools need the services), so [harness]'s Agent management, Sub-agents and Teams are bound here, once.
 */
internal class ServerAssembly(
    val harness: AgentRuntime,
    auth: PeerRegistry,
    name: String,
    scope: CoroutineScope,
    private val hub: ServerHub,
    val executions: ExecutionRegistry,
    private val executionFactory: ExecutionFactory?,
    nativeFactory: ExecutionFactory,
    executionStorage: ExecutionRecoveryStore,
    val remoteLinks: RemoteLinks,
    remoteHarness: RemoteHarnessFactory?,
    prepareAttachment: (UploadAttachmentParams) -> UploadAttachmentParams,
) {
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
    val external = ExecutionCoordinator(executions, installedFactory, scope, harness.log, executionStorage,
        resolve = { harness.attachments.download(it) }, readinessChanged = { catalogChanged() }, toolCatalog = harness.composer::externalTools)
    suspend fun persistExecutionAttachment(state: ExecutionState, data: AttachmentData): Attachment {
        external.currentStateOwner(state)
        require(attachmentMimeType(data.attachment.name) == data.attachment.mimeType) { "Output filename and MIME differ" }
        return harness.attachments.upload(UploadAttachmentParams(data.attachment.name, data.base64)).also {
            require(it.mimeType == data.attachment.mimeType) { "Stored output MIME differs" }
        }
    }
    val screenBudget = ScreenBudget()
    val runs = Runs(harness.log, scope, nativeFactory)
    val devicePush = devicePush(auth, harness.serverId)
    val notifications = Notifications(harness, scope, runs::routine, { external.registrationOrArchived(AgentId(it))?.name }, devicePush)
    val workflows: Workflows = Workflows(harness, runs, scope, { hub.broadcast(Api.WorkflowsChanged, it) }, { hub.broadcast(Api.WorkflowRunChanged, it) }, wakeScheduler = { scheduler.wakeUp() }).also { harness.workflows = it }
    val scheduler: Scheduler = Scheduler(harness, runs, scope, onRan = { hub.broadcast(Api.RoutineRan, it); notifications.routineRan(it) }, workflowTick = workflows::tick)
    val admin = Admin(harness, runs) { hub.broadcast(Api.AgentsChanged, it) }.also { harness.agentManagement = AgentManagement(harness, it) }
    private val reflectionReview = ReflectionReview(harness)
    val logins = ProviderLogins(harness.modelProviders, scope) { hub.broadcast(Api.ProvidersChanged, it); hub.changedCapabilities() }
    private val reflector = Reflector(harness, scope, runs::isActive)
    val dispatch = AgentExecution(executions, external,
        NativeExecution(harness, runs, scope, { admin.agent(it) }, { hub.capabilities }, reflector::ran) { sessionId, text -> hub.notifyVoice(sessionId, text) }, executionManagement).also { it.workflowSurface = workflows::submitSurface }
    private val subAgents = SubAgents(harness, dispatch, scope, runs::join).also { harness.subAgents = it; dispatch.subAgents = it; runs.onBegin = { _, id -> it.begin(id) }; runs.onFinish(it::finish); runs.onCancel = it::cancelChildren }
    val dispatcher = TaskDispatcher(harness, runs, scope)
    private val sessions = SessionService(harness, admin, external)
    private val routines = RoutineService(harness, admin, sessions) { hub.scheduling }
    val browserAccess = BrowserAccess(harness, executions, admin)
    val voice = VoiceService(harness, hub::changedCapabilities)
    val execution: ExecutionService = ExecutionService(harness, admin, executions, external, dispatch, executionManagement, installedFactory, remoteFactory, remoteLinks, remoteGateway, name, { hub.capabilities }, { hub.sequence }) { catalog, agents ->
        hub.broadcast(Api.ExecutionCatalogChanged, catalog)
        hub.broadcast(Api.AgentsChanged, agents)
        hub.changedCapabilities()
    }
    private val teams = Teams(harness, dispatch, scope, runs::join, execution::agents) { hub.broadcast(Api.TeamChanged, it) }.also { teams -> harness.teams = teams; runs.onFinish { _, session, cancelled -> teams.finished(session, cancelled) } }
    val services = ServerServices(harness, auth, admin, execution, dispatch, sessions, teams, routines, workflows, browserAccess, voice, reflectionReview, logins, notifications, harness.log, harness.knowledge, harness.tasks, harness.artifacts, harness.mcp, harness.secrets, harness.computers, harness, prepareAttachment, hub::changedCapabilities)

    init {
        harness.browser?.authorize { owner ->
            throwing<ServerError, Unit> {
                val agent = browserAccess.validate(owner)
                if ("browser" !in agent.config.tools) raise(BrowserError.Disabled)
            }
        }
        harness.log.executionMetadata = { info -> if (external.owns(info.id)) info.copy(legacyOrder = external.record(info.id).legacyOrder) else info }
        harness.log.onDelete = { a, id -> subAgents.cancelChildren(id); dispatch.stop(id); runs.join(id); external.delete(a, id) }; harness.computers.browserService = { harness.browser }; harness.devices.paired = auth::toolDevices; harness.computers.paired = auth::toolDevices
    }

    private suspend fun catalogChanged() { execution.catalogChanged() }
}
