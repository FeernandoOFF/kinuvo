package kinuvo.server

import arrow.core.raise.Raise
import arrow.core.raise.context.raise
import kinuvo.harness.Agent
import kinuvo.server.plugin.CUSTOM_PROVIDERS
import kinuvo.server.plugin.McpManagement
import kinuvo.server.plugin.checkPluginInstances
import kinuvo.server.devices.*
import kinuvo.server.screens.*
import kinuvo.server.plugin.*

import kinuvo.harness.BUILT_IN_HARNESS
import kinuvo.harness.NativeHarness
import kinuvo.harness.NativeHarnessHost
import kinuvo.harness.builtInRegistration
import kinuvo.server.skills.slashActivation
import kinuvo.server.knowledge.KnowledgePlugin
import kinuvo.server.knowledge.Reflection
import kinuvo.server.knowledge.ReflectionRevision
import kinuvo.server.tasks.TasksPlugin
import kinuvo.server.plugin.VoiceProviders
import kotlinx.datetime.todayIn
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kinuvo.protocol.reflection.ReflectionResult
import kinuvo.protocol.session.SessionEntry
import kinuvo.harness.with
import kinuvo.server.tools.AgentManagement
import kotlinx.serialization.json.*
import kinuvo.server.store.Store
import kinuvo.harness.ExecutionBroker
import kinuvo.harness.ExecutionInput
import kinuvo.harness.ExecutionState
import kinuvo.harness.RunKind
import kinuvo.protocol.tool.ToolNames
import kinuvo.protocol.approval.PendingInteraction
import kinuvo.protocol.approval.InteractionAnswer
import kinuvo.server.execution.forHarness
import kinuvo.server.execution.approvalDecision
import kinuvo.protocol.routine.RoutineRef
import kinuvo.protocol.session.SessionInfo
import kinuvo.protocol.session.SessionSettings
import kinuvo.protocol.usage.Usage
import kinuvo.protocol.capability.ServerFeature
import kinuvo.protocol.capability.Integration
import kinuvo.protocol.device.Platform
import kinuvo.protocol.device.currentPlatform
import kotlinx.coroutines.flow.StateFlow
import kinuvo.protocol.session.SessionId

val BUILT_IN_FEATURES = setOf(ServerFeature.Attachments, ServerFeature.Skills,
    ServerFeature.Artifacts, ServerFeature.Routines, ServerFeature.DeviceTools, ServerFeature.Computer)

/** Executes Agents with the repositories and resources supplied by the Server installation. */
open class AgentRuntime(
    private val dependencies: RuntimeDependencies,
    /** Builds the built-in harness for this runtime, with the model sources a host registered with it. */
    nativeSessions: (NativeHarnessHost) -> NativeHarness,
    val platform: Platform = currentPlatform,
    val memoryCap: Int = 4000,
    val userCap: Int = 4000,
    val secrets: Secrets = dependencies.secrets,
    private val builtInFeatures: Set<ServerFeature> = BUILT_IN_FEATURES,
    platformDescription: String = platform.displayName,
    /** How long an unattended Routine run waits for an Approval before skipping the call. */
    routineApprovalWait: Duration = 30.minutes,
    /** The harness builds models without a registered provider, e.g. scripted ones in tests, so no provider is asked whether it is ready. */
    unmanagedModels: Boolean = false,
    val allowedTools: Set<String>? = null,
) : ServerSettings by dependencies.settings {
    var workflows: Workflows? = null
    var agentManagement: AgentManagement? = null
    var subAgents: SubAgents? = null
    var teams: Teams? = null
    val log = SessionLog(dependencies.store, dependencies.clock)
    val store: Store = log
    val docs = dependencies.docs
    val skills = dependencies.skills
    val trustRules = dependencies.trustRules
    val serverId = dependencies.serverId
    val attachments = dependencies.attachments
    val artifacts = dependencies.artifacts
    val routines = dependencies.routines
    val knowledge = dependencies.knowledge
    val tasks = dependencies.tasks
    val reflectionProgress = dependencies.reflectionProgress
    private val external = dependencies.plugins
    val browser get() = external.firstNotNullOfOrNull { it.browser }
    val voice: VoiceProviders = dependencies.voice
    val serverVoice = external.firstNotNullOfOrNull { it.voice }
    /** Knowledge and Tasks come with any provider for them, so a Server offers them whenever it has one. */
    val features get() = builtInFeatures + external.flatMap { it.features } +
        PROVIDED_FEATURES.filterKeys { dependencies.settings.integrations[it].orEmpty().isNotEmpty() }.values
    val mcp: McpManagement = dependencies.mcp
    val execCheck = dependencies.execCheck
    val devices = DeviceTools(log, { questions }, { timeZone })
    val computers = Computers(log) { questions }
    internal val plugins = (listOf(KnowledgePlugin(knowledge), TasksPlugin(tasks), devices, computers) + external).also { it.checkPluginInstances() }
    private val reflection = Reflection(knowledge, docs, skills, { trustRules.effective(it.id).deny }) { Clock.System.todayIn(timeZone) }
    internal val providers get() = dependencies.modelProviders.associateBy { it.id }
    suspend fun modelCatalog(refresh: Boolean = false) = dependencies.modelCatalog(refresh)
    val modelProviders get() = dependencies.modelProviders
    context(_: Raise<ProviderError>)
    fun customEndpoints() = dependencies.plugins.firstNotNullOfOrNull { it.customEndpoints } ?: raise(ProviderError.NoCustomEndpoints)
    fun requireModelProvider(id: String) { require(providerId(id) in providers) { "No model provider '$id'" } }
    /** A custom provider's bare ID, as agent.yaml named it before custom IDs were prefixed, still finds it. */
    fun providerId(id: String) = if (id !in providers && CUSTOM_PROVIDERS + id in providers) CUSTOM_PROVIDERS + id else id
    val usage: StateFlow<Usage?> = dependencies.usage

    /** Whether conversations with [agent] are reflected on afterwards. */
    fun reflects(agent: Agent) = ServerFeature.Knowledge in features && agent.config.tools.any { it in setOf(ToolNames.KNOWLEDGE, ToolNames.MEMORY, ToolNames.SKILLS) } && reflectionEnabled

    /** The built-in harness, run with this runtime's tools, instructions and models. */
    val composer = RunComposer(this, dependencies, platformDescription, routineApprovalWait, unmanagedModels)
    val native: NativeHarness = nativeSessions(composer)

    /** Saves what's worth keeping from [entries] of a Session. */
    suspend fun reflect(agent: Agent, sessionId: SessionId, entries: List<SessionEntry>, review: ReflectionRevision? = null): ReflectionResult {
        val turn = reflection.turn(composer.reflectedAgent(agent, sessionId), sessionId, entries, reflectionPrompt, review)
        val state = ExecutionState(sessionId)
        val session = native.open(BUILT_IN_HARNESS, builtInRegistration(agent), state, ExecutionBroker(state), CoroutineScope(currentCoroutineContext()))
        try { session.prompt(ExecutionInput("", kind = RunKind.Reflection(turn))) }
        finally { session.close() }
        return turn.finish()
    }

    suspend fun newSession(agent: Agent, routine: RoutineRef? = null): SessionInfo {
        return store.createSession(agent.id, instructionsFor(agent, docs), routine)
    }

    /** Replaces [sessionId]'s own settings; its next run uses them. */
    suspend fun configure(agent: Agent, sessionId: SessionId, settings: SessionSettings): SessionInfo {
        val s = checkConfigure(agent, sessionId, settings)
        if (s != store.session(agent.id, sessionId).settings) store.append(agent.id, sessionId, SessionEntry.Settings(s))
        return store.session(agent.id, sessionId)
    }

    /** [settings] as [configure] would save them; fails when they aren't allowed for [sessionId]. */
    suspend fun checkConfigure(agent: Agent, sessionId: SessionId, settings: SessionSettings): SessionSettings {
        if (store.session(agent.id, sessionId).hidden) require(settings == store.session(agent.id, sessionId).settings) { "Hidden Session settings are pinned" }
        val s = settings.copy(model = settings.model?.trim()?.ifEmpty { null }, reasoning = settings.reasoning?.trim()?.ifEmpty { null },
            approvals = settings.approvals?.trim()?.ifEmpty { null }, provider = settings.provider?.trim()?.ifEmpty { null }?.let(::providerId))
        s.provider?.let { requireModelProvider(it); require(s.model != null) { "Choose a model with its provider" } }
        require(s.approvals == null || s.approvals in Tolerance.ids) { "approvals must be one of ${Tolerance.ids.joinToString()}" }
        return s
    }

    /** Renames [sessionId] (blank: back to its first message) or marks it a favorite. */
    suspend fun update(agent: Agent, sessionId: SessionId, title: String?, favorite: Boolean?): SessionInfo =
        log.updateDetails(agent.id, sessionId, title, favorite)

    /** `/name rest` activates a user-invocable Skill; returns null when [text] isn't a Skill command. */
    suspend fun slashActivation(agent: Agent, text: String) = skills.slashActivation(agent, text)

    val approvals = ApprovalBroker()
    internal val sessionTrust = SessionTrust()
    /** Pending `ask_user` forms; null answers mean skipped. */
    val questions = Broker<Map<String, List<String>>?>()
    val uiCatalogs = SurfaceCatalogs()
    val appCalls = AppCalls(log, { plugins.firstNotNullOfOrNull { it.apps } }, secrets, trustRules, sessionTrust, approvals)

    /** Answers a Server tool's interaction, including the neutral form used by external harness clients. */
    fun respondToTool(pending: PendingInteraction, answer: InteractionAnswer): Boolean = when {
        pending is PendingInteraction.Approval && answer is InteractionAnswer.Approval -> approvals.respond(pending.id, answer.decision)
        pending is PendingInteraction.Approval && answer is InteractionAnswer.Harness -> {
            val option = (answer.answer as? JsonObject)?.get("optionId")?.jsonPrimitive?.content
            require(option == null || pending.forHarness().options.any { it.id == option })
            approvals.respond(pending.id, approvalDecision(option))
        }
        pending is PendingInteraction.Questions && answer is InteractionAnswer.Questions -> questions.respond(pending.id, answer.answers)
        else -> false
    }

    /** A file from [agent]'s Workspace, e.g. to copy into an Artifact. */
    suspend fun readFile(agent: Agent, path: String): String =
        plugins.firstNotNullOfOrNull { it.readFile(agent, path) } ?: error("${agent.id} has no Workspace")

    /** Stops everything the plugins run, e.g. before the process exits. */
    suspend fun close() { computers.close(); try { native.close() } finally { dependencies.resources.close() } }

    /** Stops what the plugins run for the Agent, e.g. after its exec settings changed. */
    suspend fun release(agent: Agent) = dependencies.resources.release(agent.id)

    override val integrations get() = dependencies.settings.integrations + (Integration.Tools to composer.toolGroups)

    private companion object {
        val PROVIDED_FEATURES = mapOf(Integration.Knowledge to ServerFeature.Knowledge, Integration.Tasks to ServerFeature.Tasks)
    }
}
