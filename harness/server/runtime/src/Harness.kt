package kinuvo.harness.server

import kinuvo.harness.core.*
import kinuvo.harness.core.routines.ScheduleTool
import kinuvo.harness.core.skills.ActivateSkillTool
import kinuvo.harness.core.skills.ReadSkillFileTool
import kinuvo.harness.core.skills.SkillManageTool
import kinuvo.harness.core.skills.load
import kinuvo.harness.core.skills.slashActivation
import kinuvo.harness.core.knowledge.KnowledgePlugin
import kinuvo.harness.core.knowledge.Reflection
import kinuvo.harness.core.knowledge.ReflectionRevision
import kinuvo.harness.core.tasks.TasksPlugin
import kinuvo.harness.core.voice.VoiceProviders
import kotlinx.datetime.todayIn
import kotlin.time.Clock
import kinuvo.protocol.SessionEntry
import kinuvo.harness.core.store.Store
import kinuvo.harness.core.artifacts.ArtifactTool
import kinuvo.harness.core.tools.AskUserTool
import kinuvo.harness.core.tools.MemoryTool
import kinuvo.harness.core.tools.Tool
import kinuvo.harness.core.tools.indexedTools
import kinuvo.harness.core.tools.ToolNames
import kinuvo.harness.core.tools.ToolCatalog
import kinuvo.harness.core.effective
import kinuvo.harness.core.tools.WebSearch
import kinuvo.harness.core.tools.WebSearchTool
import kinuvo.protocol.ApprovalDecision
import kinuvo.protocol.Routine
import kinuvo.protocol.RoutineApprovals
import kinuvo.protocol.RoutineRef
import kinuvo.protocol.SessionInfo
import kinuvo.protocol.SessionSettings
import kinuvo.protocol.Usage
import kinuvo.protocol.ServerFeature
import kinuvo.protocol.Integration
import kinuvo.protocol.CapabilityState
import kinuvo.protocol.Support
import kinuvo.protocol.Authorization
import kinuvo.protocol.ResolvedTool
import kinuvo.protocol.SessionCapabilities
import kinuvo.protocol.ServerCapabilities
import kinuvo.protocol.Platform
import kinuvo.protocol.currentPlatform
import kinuvo.protocol.ModelInfo
import kotlinx.coroutines.flow.StateFlow

val BUILT_IN_FEATURES = setOf(ServerFeature.Attachments, ServerFeature.Skills,
    ServerFeature.Artifacts, ServerFeature.Routines, ServerFeature.DeviceTools, ServerFeature.Computer)

/** Executes Agents with the repositories and resources supplied by the Server installation. */
open class HarnessRuntime(
    private val dependencies: RuntimeDependencies,
    private val modelFactory: ((AgentConfig) -> AgentModel)? = null,
    val platform: Platform = currentPlatform,
    val memoryCap: Int = 4000,
    val userCap: Int = 4000,
    val secrets: Secrets = dependencies.secrets,
    private val builtInFeatures: Set<ServerFeature> = BUILT_IN_FEATURES,
    private val platformDescription: String = platform.displayName,
    val voice: VoiceProviders = VoiceProviders(),
) : ServerSettings by dependencies.settings {
    val log = SessionLog(dependencies.store)
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
    /** Knowledge and Tasks come with any provider for them, so a Server offers them whenever it has one. */
    val features get() = builtInFeatures + external.flatMap { it.features } +
        PROVIDED_FEATURES.filterKeys { dependencies.settings.integrations[it].orEmpty().isNotEmpty() }.values
    val mcp: McpManagement = dependencies.mcp
    val execCheck = dependencies.execCheck
    val devices = DeviceTools(log, { questions }, { timeZone })
    val computers = Computers(log) { questions }
    private val plugins = (listOf(KnowledgePlugin(knowledge), TasksPlugin(tasks), devices, computers) + external).also { it.checkPluginInstances() }
    private val reflection = Reflection(knowledge, docs, skills, { trustRules.effective(it.id).deny }, tracing = dependencies.tracing) { Clock.System.todayIn(timeZone) }
    private val providers = dependencies.modelProviders.associateBy { it.id }
    suspend fun modelCatalog(refresh: Boolean = false) = dependencies.modelCatalog(refresh)
    val modelProviders get() = providers.values.toList()
    fun requireModelProvider(id: String) { require(id in providers) { "No model provider '$id'" } }
    val usage: StateFlow<Usage?> = dependencies.usage
    private val modelFactoryOverride = modelFactory != null
    private fun model(e: Effective): AgentModel = modelFactory?.invoke(e.agent.config)
        ?: (e.provider ?: error("No model provider '${e.agent.config.provider}'")).model(e.agent.config, e.info)

    /** Whether conversations with [agent] are reflected on afterwards. */
    fun reflects(agent: Agent) = ServerFeature.Knowledge in features && agent.config.tools.any { it in setOf(ToolNames.KNOWLEDGE, ToolNames.MEMORY, ToolNames.SKILLS) } && reflectionEnabled

    /** Saves what's worth keeping from [entries] of a Session. */
    suspend fun reflect(agent: Agent, sessionId: String, entries: List<SessionEntry>, review: ReflectionRevision? = null): kinuvo.protocol.ReflectionResult {
        val resolved = try { agent.with(store.session(agent, sessionId).settings) }
        catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { agent }
        val chat = effective(resolved, null)
        val chosen = try {
            val selected = reflectionModel
            val provider = providers[selected.provider ?: chat.agent.config.provider]
            val catalog = modelCatalog().providers.firstOrNull { it.id == provider?.id }?.models.orEmpty()
            val id = selected.model
            val info = catalog.firstOrNull { it.id == id && it.tools != false && it.state.actionable }
            if (provider != null && info != null && provider.availability(info.id).actionable) {
                val reflected = chat.agent.copy(config = chat.agent.config.copy(provider = provider.id, model = info.id))
                reflected to model(Effective(reflected, info, provider, chat.memoryCap, chat.userCap))
            } else null
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }
        val (reflected, model) = chosen ?: (chat.agent to model(chat))
        return reflection.reflect(reflected, model, sessionId, entries, reflectionPrompt, review)
    }

    suspend fun newSession(agent: Agent, routine: RoutineRef? = null): SessionInfo {
        return store.createSession(agent, instructionsFor(agent, docs), routine)
    }

    /** Replaces [sessionId]'s own settings; its next run uses them. */
    suspend fun configure(agent: Agent, sessionId: String, settings: SessionSettings): SessionInfo {
        val s = settings.copy(model = settings.model?.trim()?.ifEmpty { null }, reasoning = settings.reasoning?.trim()?.ifEmpty { null },
            approvals = settings.approvals?.trim()?.ifEmpty { null }, provider = settings.provider?.trim()?.ifEmpty { null })
        s.provider?.let { requireModelProvider(it); require(s.model != null) { "Choose a model with its provider" } }
        require(s.approvals == null || s.approvals in Tolerance.ids) { "approvals must be one of ${Tolerance.ids.joinToString()}" }
        if (s != store.session(agent, sessionId).settings) store.append(agent, sessionId, SessionEntry.Settings(s))
        return store.session(agent, sessionId)
    }

    /** Renames [sessionId] (blank: back to its first message) or marks it a favorite. */
    suspend fun update(agent: Agent, sessionId: String, title: String?, favorite: Boolean?): SessionInfo {
        val before = store.entries(agent, sessionId).filterIsInstance<SessionEntry.Details>().lastOrNull() ?: SessionEntry.Details()
        val details = SessionEntry.Details(
            title = if (title == null) before.title else title.trim().take(MAX_TITLE).ifEmpty { null },
            favorite = favorite ?: before.favorite,
        )
        if (details != before) store.append(agent, sessionId, details)
        return store.session(agent, sessionId)
    }

    /** `/name rest` activates a user-invocable Skill; returns null when [text] isn't a Skill command. */
    suspend fun slashActivation(agent: Agent, text: String) = skills.slashActivation(agent, text)

    val approvals = ApprovalBroker()
    /** Pending `ask_user` forms; null answers mean skipped. */
    val questions = Broker<Map<String, List<String>>?>()

    /**
     * The Agent as a Session runs it: its settings applied, tool groups this Server lacks dropped, and compaction and
     * Memory limits fitted to the model's context window. Capabilities, instructions and runs all start from this.
     */
    private class Effective(val agent: Agent, val info: ModelInfo?, val provider: ModelProvider?, val memoryCap: Int, val userCap: Int)

    private suspend fun effective(agent: Agent, sessionId: String?, settings: SessionSettings? = null): Effective {
        val configured = supported(agent.with(settings ?: sessionId?.let { store.session(agent, it).settings } ?: SessionSettings()))
        val info = modelCatalog().providers.firstOrNull { it.id == configured.config.provider }?.models?.firstOrNull { it.id == configured.config.model }
        val context = info?.context
        val fitted = if (context == null) configured else configured.copy(config = configured.config.copy(compactAtTokens = minOf(configured.config.compactAtTokens, context * 55 / 100)))
        fun cap(limit: Int) = context?.let { minOf(limit, it * 3 / 10) } ?: limit
        return Effective(fitted, info, providers[configured.config.provider], cap(memoryCap), cap(userCap))
    }

    /** Which of [tools] the model takes: none when it can't call tools, else what its catalog entry and provider allow. */
    private fun usable(e: Effective, tools: List<Tool>): Set<String> {
        if (e.info?.tools == false) return emptySet()
        val listed = tools.filter { t -> e.info?.toolIds?.let { t.identity?.key in it } != false }.map { it.descriptor }
        return (e.provider?.usableTools(e.agent.config.model, listed) ?: listed).map { it.name }.toSet()
    }

    /** [tools] can all be called; the model is sent only [advertised], with catalogued tools behind the catalog's. */
    private class PreparedRun(val effective: Effective, val tools: List<Tool>, val advertised: List<Tool>, val catalog: List<ResolvedTool>, val warnings: List<String>, val chat: CapabilityState)

    private suspend fun prepare(agent: Agent, sessionId: String?, settings: SessionSettings? = null,
        routine: Routine? = null, taskId: String? = null, activate: Boolean = false): PreparedRun {
        val e = effective(agent, sessionId, settings)
        val chat = if (modelFactoryOverride) CapabilityState() else e.provider?.availability(e.agent.config.model)
            ?: CapabilityState.unsupported("No model provider '${e.agent.config.provider}'; this model provider is not installed")
        if (activate) chat.requireReady()
        val run = RunContext(e.agent, sessionId, routine, taskId)
        val contributed = plugins.map { it.prepare(run, activate).ownedBy(it.instance, it.manifest) }
        val own = PluginTools(toolsFor(e, sessionId, routine)).ownedBy(kinuvo.protocol.PluginInstanceId("kinuvo.core")).tools
        val all = (own + contributed.flatMap { it.tools }).also { it.indexedTools() }
        val eligible = usable(e, all)
        val descriptors = all.map { it.descriptor.name }.toSet()
        val rules = trustRules.effective(agent.id)
        val catalog = (own.map { ResolvedTool(it.descriptor.name, it.displayName, identity = it.identity) } +
            contributed.flatMap { it.catalog }).distinctBy { (it.identity?.key ?: it.id) to it.target }.map { tool ->
            val state = when {
                e.info?.tools == false || e.info?.toolIds?.let { tool.identity?.localId != "*" && tool.identity?.key !in it } == true ||
                    tool.id in descriptors && tool.id !in eligible -> CapabilityState.unsupported("This model cannot call ${tool.name}")
                rules.deny.blockedTool(tool.identity) != null -> tool.state.copy(authorization = Authorization.Denied, reason = "Blocked by a Deny rule", reasonCode = "policy")
                e.info?.tools == null -> tool.state.copy(support = Support.Unknown, reason = tool.state.reason ?: "Model tool support is unknown")
                else -> tool.state
            }
            tool.copy(state = state)
        }
        val usableTools = all.filter { it.descriptor.name in eligible }
        val cataloguedNames = plugins.zip(contributed).filter { it.first.catalogued }.flatMap { it.second.tools }.map { it.descriptor.name }.toSet()
        val candidates = if (usableTools.size > ToolCatalog.LIMIT) usableTools.filter { it.descriptor.name in cataloguedNames } else emptyList()
        // Only when the model can take the catalog's own tools; otherwise every tool stays direct.
        val finders = if (candidates.isEmpty()) emptyList() else PluginTools(ToolCatalog.tools(candidates)).ownedBy(kinuvo.protocol.PluginInstanceId("kinuvo.core")).tools
            .takeIf { f -> usable(e, f).size == f.size }.orEmpty()
        val hidden = if (finders.isEmpty()) emptyList() else candidates
        val reported = catalog + finders.map { ResolvedTool(it.descriptor.name, it.displayName, identity = it.identity) }
        return PreparedRun(e, usableTools + finders, usableTools - hidden.toSet() + finders, reported, contributed.flatMap { it.warnings }, chat)
    }

    suspend fun capabilities(agent: Agent, sessionId: String?, settings: SessionSettings?, server: ServerCapabilities, revision: Long = 0): SessionCapabilities {
        val prepared = prepare(agent, sessionId, settings)
        val info = prepared.effective.info
        val chat = prepared.chat
        fun input(kind: String): CapabilityState = when {
            kind != "text" && ServerFeature.Attachments !in server.features -> CapabilityState.unsupported("Attachments are not installed on this Server")
            info?.input == null -> if (kind == "text") chat else CapabilityState.unknown("Model input support is unknown")
            kind !in info.input.orEmpty() -> CapabilityState.unsupported("This model does not accept $kind input")
            else -> chat
        }
        return SessionCapabilities(revision, server, chat, listOf("text", "image", "file", "audio").associateWith(::input), prepared.catalog)
    }

    /**
     * Builds a runner with the Agent's current tools, starting what its plugins need on first use. [sessionId] is the
     * Session it runs in; [routine] is set for a Routine's run, which follows the Routine's approval mode.
     */
    suspend fun runner(agent: Agent, sessionId: String? = null, routine: Routine? = null, taskId: String? = null): AgentRun {
        val prepared = prepare(agent, sessionId, routine = routine, taskId = taskId, activate = true)
        val e = prepared.effective
        val agent = e.agent
        val tools = prepared.tools.map(secrets::redacting)
        val advertised = prepared.advertised.map { it.descriptor.name }.toSet().let { names -> tools.filter { it.descriptor.name in names } }
        val sections = plugins.mapNotNull { it.instructions(agent) }
        val context = kinuvo.harness.core.personalContextFor(agent, docs) + "\n" + kinuvo.harness.core.capabilitiesFor(agent, advertised) + "\nServer platform: $platformDescription. Today is ${Clock.System.todayIn(timeZone)} ($timeZone).\n" + sections.joinToString("\n\n") { "## ${it.title}\n\n${it.body}" } +
            "\n\nCurrent Skills:\n" + kinuvo.harness.core.skillCatalogFor(if (ToolNames.SKILLS in agent.config.tools) skills.load(agent) else emptyList())
        val model = model(e)
        return AgentRun(
            agent = agent,
            store = store,
            model = model,
            contextTokens = e.info?.context,
            tracing = dependencies.tracing,
            tools = tools,
            advertisedTools = advertised.map { it.descriptor },
            context = secrets.redact(context),
            warnings = prepared.warnings.map { secrets.redact(it) },
            approvals = when (routine?.approvals) {
                null -> Approvals(ApprovalPolicy(agent, trustRules), approvals)
                RoutineApprovals.Auto -> Approvals(ApprovalPolicy(agent, trustRules, Tolerance.Full), approvals)
                RoutineApprovals.Deny -> Approvals(ApprovalPolicy(agent, trustRules, routineTolerance(agent)), approvals, ApprovalDecision.Deny(UNATTENDED_DENIAL))
                RoutineApprovals.Inherit -> Approvals(ApprovalPolicy(agent, trustRules, routineTolerance(agent)), approvals)
            },
            questions = questions,
            attach = { name, bytes -> attachments.upload(kinuvo.protocol.UploadAttachmentParams(name, kotlin.io.encoding.Base64.encode(bytes))) },
        )
    }

    /** Routines run Relaxed, so only High-risk calls wait or are denied, unless the Agent never asks. */
    private fun routineTolerance(agent: Agent) = if (Tolerance.of(agent.config.approvals) == Tolerance.Full) Tolerance.Full else Tolerance.Relaxed

    /** A file from [agent]'s Workspace, e.g. to copy into an Artifact. */
    suspend fun readFile(agent: Agent, path: String): String =
        plugins.firstNotNullOfOrNull { it.readFile(agent, path) } ?: error("${agent.id} has no Workspace")

    /** Stops everything the plugins run, e.g. before the process exits. */
    suspend fun close() { computers.close(); dependencies.resources.close() }

    /** Stops what the plugins run for the Agent, e.g. after its exec settings changed. */
    suspend fun release(agent: Agent) = dependencies.resources.release(agent.id)

    private suspend fun toolsFor(e: Effective, sessionId: String?, routine: Routine?): List<Tool> {
        val agent = e.agent
        val search = if (ToolNames.WEB_FETCH in agent.config.tools) webSearch() else null
        return builtInTools(e, sessionId, routine, search) + listOfNotNull(search?.let(::WebSearchTool))
    }

    private suspend fun builtInTools(e: Effective, sessionId: String?, routine: Routine?, search: WebSearch?): List<Tool> = e.agent.let { agent -> agent.config.tools.mapNotNull {
        when (it) {
            ToolNames.MEMORY -> MemoryTool(agent, docs, memoryCap = e.memoryCap, userCap = e.userCap)
            ToolNames.WEB_FETCH -> dependencies.webFetch(search)
            ToolNames.SCHEDULE -> ScheduleTool(agent, routines, { timeZone }, sessionId, routine)
            ToolNames.ASK_USER -> AskUserTool(routine != null).takeIf { routine?.approvals != RoutineApprovals.Deny }
            ToolNames.ARTIFACT -> ArtifactTool(agent, sessionId, artifacts) { readFile(agent, it) }
            else -> null
        }
    } + if (ToolNames.SKILLS in agent.config.tools) skillTools(agent) else emptyList() }

    /** [agent] without the tool groups this Server lacks the feature for, so neither its tools nor their instructions show. */
    private fun supported(agent: Agent) = agent.copy(config = agent.config.copy(tools = agent.config.tools.filter { it in toolGroups }))

    /** The built-in tool groups this Server can run: its own, where it has their feature, and those its plugins add. */
    private val toolGroups get() = ToolNames.OPTIONAL.filter { it !in ToolNames.FROM_PLUGINS && TOOL_FEATURES[it]?.let(features::contains) != false }.toSet() +
        dependencies.settings.integrations[Integration.Tools].orEmpty()

    override val integrations get() = dependencies.settings.integrations + (Integration.Tools to toolGroups)

    private fun skillTools(agent: Agent): List<Tool> =
        listOf(ActivateSkillTool(agent, skills), ReadSkillFileTool(agent, skills), SkillManageTool(agent, skills))

    private companion object {
        val PROVIDED_FEATURES = mapOf(Integration.Knowledge to ServerFeature.Knowledge, Integration.Tasks to ServerFeature.Tasks)
        val TOOL_FEATURES = mapOf(ToolNames.SKILLS to ServerFeature.Skills, ToolNames.SCHEDULE to ServerFeature.Routines, ToolNames.ARTIFACT to ServerFeature.Artifacts,
            ToolNames.KNOWLEDGE to ServerFeature.Knowledge, ToolNames.TASKS to ServerFeature.Tasks, ToolNames.DEVICE_TOOLS to ServerFeature.DeviceTools,
            ToolNames.COMPUTER to ServerFeature.Computer)
        const val MAX_TITLE = 100
        const val UNATTENDED_DENIAL = "This is an unattended Routine run, so tool calls that need an Approval are denied."
    }
}
