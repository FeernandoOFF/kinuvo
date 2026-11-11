package kinuvo.server

import kinuvo.protocol.approval.InteractionId
import arrow.core.raise.Raise
import arrow.core.raise.context.raise
import kinuvo.harness.Agent
import kinuvo.server.plugin.CUSTOM_PROVIDERS
import kinuvo.server.plugin.McpManagement
import kinuvo.server.plugin.PluginTools
import kinuvo.server.plugin.RunContext
import kinuvo.server.plugin.checkPluginInstances
import kinuvo.server.plugin.instance
import kinuvo.server.plugin.ownedBy
import kinuvo.server.plugin.withModelNames
import kinuvo.server.devices.*
import kinuvo.server.screens.*
import kinuvo.server.plugin.*
import kinuvo.protocol.session.ATTACHMENT_MIME_TYPES
import kinuvo.protocol.session.attachmentInput

import kinuvo.harness.BUILT_IN_HARNESS
import kinuvo.harness.AgentRunner
import kinuvo.harness.ModelProfile
import kinuvo.harness.ModelSelection
import kinuvo.harness.NativeHarness
import kinuvo.harness.NativeHarnessHost
import kinuvo.harness.NativeReflection
import kinuvo.harness.NativeRun
import kinuvo.harness.PromptStyle
import kinuvo.harness.ResultLimits
import kinuvo.harness.builtInRegistration
import kinuvo.harness.inferTier
import kinuvo.server.tools.GenerateImageTool
import kinuvo.server.tools.ShowUiTool
import kinuvo.server.tools.ShowHtmlTool
import kinuvo.protocol.surface.UiCatalog
import kinuvo.server.routines.RoutineReportTool
import kinuvo.server.routines.ScheduleTool
import kinuvo.server.skills.ActivateSkillTool
import kinuvo.server.skills.ReadSkillFileTool
import kinuvo.server.skills.SkillManageTool
import kinuvo.server.skills.load
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
import kotlin.uuid.Uuid
import kinuvo.protocol.session.AgentEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kinuvo.protocol.session.SessionStatus
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kinuvo.protocol.reflection.ReflectionResult
import kinuvo.protocol.session.SessionEntry
import kinuvo.protocol.mcp.McpAppRef
import kinuvo.protocol.mcp.McpAppRequest
import kinuvo.protocol.mcp.McpAppResource
import kinuvo.protocol.mcp.McpAppToolRequest
import kinuvo.server.plugin.McpApps
import kinuvo.server.plugin.ToolRun
import kinuvo.harness.simplified
import kinuvo.harness.with
import kinuvo.server.tools.ToolScope
import kinuvo.server.tools.select
import kinuvo.protocol.model.ModelTier
import kotlinx.serialization.json.*
import kinuvo.server.store.Store
import kinuvo.server.artifacts.ArtifactTool
import kinuvo.server.tools.AskUserTool
import kinuvo.server.tools.MemoryTool
import kinuvo.server.plugin.Tool
import kinuvo.server.plugin.indexedTools
import kinuvo.server.tools.Toolsets
import kinuvo.harness.ToolReports
import kinuvo.server.tools.ToolService
import kinuvo.harness.ExecutionBroker
import kinuvo.harness.ExecutionInput
import kinuvo.harness.ExecutionState
import kinuvo.harness.RoutineTurn
import kinuvo.harness.RunKind
import kinuvo.harness.ToolTurn
import kinuvo.protocol.tool.ToolNames
import kinuvo.server.tools.ToolCatalog
import kinuvo.server.plugin.WebSearch
import kinuvo.server.plugin.SearchRegion
import kinuvo.server.tools.WebSearchTool
import kinuvo.protocol.approval.PendingInteraction
import kinuvo.protocol.approval.InteractionAnswer
import kinuvo.server.execution.forHarness
import kinuvo.server.execution.approvalDecision
import kinuvo.protocol.approval.ApprovalDecision
import kinuvo.protocol.routine.RoutineApprovals
import kinuvo.protocol.routine.RoutineRef
import kinuvo.protocol.session.SessionInfo
import kinuvo.protocol.session.SessionSettings
import kinuvo.protocol.usage.Usage
import kinuvo.protocol.capability.ExecutionLocation
import kinuvo.protocol.capability.ServerFeature
import kinuvo.protocol.capability.Integration
import kinuvo.protocol.capability.CapabilityState
import kinuvo.protocol.capability.Support
import kinuvo.protocol.capability.Authorization
import kinuvo.protocol.capability.ResolvedTool
import kinuvo.protocol.capability.SessionCapabilities
import kinuvo.protocol.capability.ServerCapabilities
import kinuvo.protocol.device.Platform
import kinuvo.protocol.device.currentPlatform
import kinuvo.protocol.model.ProviderModelRef
import kinuvo.protocol.model.imageModels
import kinuvo.protocol.model.resolveImageModel
import kinuvo.protocol.model.ModelInfo
import kotlinx.coroutines.flow.StateFlow
import kinuvo.protocol.plugin.PluginInstanceId
import kinuvo.protocol.session.UploadAttachmentParams
import kinuvo.protocol.agent.AgentId
import kinuvo.protocol.session.SessionId
import kinuvo.protocol.session.ToolCallId
import kotlin.io.encoding.Base64
import kotlinx.coroutines.CancellationException
import kinuvo.server.providers.ModelProvider

val BUILT_IN_FEATURES = setOf(ServerFeature.Attachments, ServerFeature.Skills,
    ServerFeature.Artifacts, ServerFeature.Routines, ServerFeature.DeviceTools, ServerFeature.Computer)

/** Executes Agents with the repositories and resources supplied by the Server installation. */
open class HarnessRuntime(
    private val dependencies: RuntimeDependencies,
    /** Builds the built-in harness for this runtime, with the model sources a host registered with it. */
    nativeSessions: (NativeHarnessHost) -> NativeHarness,
    val platform: Platform = currentPlatform,
    val memoryCap: Int = 4000,
    val userCap: Int = 4000,
    val secrets: Secrets = dependencies.secrets,
    private val builtInFeatures: Set<ServerFeature> = BUILT_IN_FEATURES,
    private val platformDescription: String = platform.displayName,
    /** How long an unattended Routine run waits for an Approval before skipping the call. */
    private val routineApprovalWait: Duration = 30.minutes,
    /** The harness builds models without a registered provider, e.g. scripted ones in tests, so no provider is asked whether it is ready. */
    private val unmanagedModels: Boolean = false,
) : ServerSettings by dependencies.settings, NativeHarnessHost {
    var subAgents: SubAgents? = null
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
    private val plugins = (listOf(KnowledgePlugin(knowledge), TasksPlugin(tasks), devices, computers) + external).also { it.checkPluginInstances() }
    private val reflection = Reflection(knowledge, docs, skills, { trustRules.effective(it.id).deny }) { Clock.System.todayIn(timeZone) }
    private val providers get() = dependencies.modelProviders.associateBy { it.id }
    suspend fun modelCatalog(refresh: Boolean = false) = dependencies.modelCatalog(refresh)
    val modelProviders get() = dependencies.modelProviders
    context(_: Raise<ProviderError>)
    fun customEndpoints() = dependencies.plugins.firstNotNullOfOrNull { it.customEndpoints } ?: raise(ProviderError.NoCustomEndpoints)
    fun requireModelProvider(id: String) { require(providerId(id) in providers) { "No model provider '$id'" } }
    /** A custom provider's bare ID, as agent.yaml named it before custom IDs were prefixed, still finds it. */
    fun providerId(id: String) = if (id !in providers && CUSTOM_PROVIDERS + id in providers) CUSTOM_PROVIDERS + id else id
    val usage: StateFlow<Usage?> = dependencies.usage
    private fun selection(e: Effective) = ModelSelection(e.agent.config, e.info)

    /** Whether conversations with [agent] are reflected on afterwards. */
    fun reflects(agent: Agent) = ServerFeature.Knowledge in features && agent.config.tools.any { it in setOf(ToolNames.KNOWLEDGE, ToolNames.MEMORY, ToolNames.SKILLS) } && reflectionEnabled

    /** The built-in harness, run with this runtime's tools, instructions and models. */
    val native: NativeHarness = nativeSessions(this)

    override suspend fun run(agentId: AgentId, sessionId: SessionId, input: ExecutionInput): NativeRun {
        val agent = store.agents().firstOrNull { it.id == agentId } ?: throw ServerException(AgentError.NotFound(agentId))
        return when (val kind = input.kind) {
            is RunKind.Chat -> compose(agent, sessionId, taskId = kind.taskId, catalogs = kind.catalogs ?: uiCatalogs.forRun(), reports = kind.reports)
            is RunKind.Routine -> compose(agent, sessionId, routine = kind.turn)
            is RunKind.Reflection -> error("A Reflection has no chat run")
        }
    }

    /** [agent] as a Session's Reflection reads it: with the Session's own settings. */
    private suspend fun reflected(agent: Agent, sessionId: SessionId): Effective {
        val resolved = try { agent.with(store.session(agent.id, sessionId).settings) }
        catch (e: CancellationException) { throw e } catch (_: Exception) { agent }
        return effective(resolved, null)
    }

    override suspend fun reflection(agentId: AgentId, sessionId: SessionId): NativeReflection {
        val chat = reflected(store.agents().firstOrNull { it.id == agentId } ?: throw ServerException(AgentError.NotFound(agentId)), sessionId)
        val own = NativeReflection(chat.agent, selection(chat), dependencies.traces)
        val chosen = try {
            val selected = reflectionModel
            val provider = providers[providerId(selected.provider ?: chat.agent.config.provider)]
            val catalog = modelCatalog().providers.firstOrNull { it.id == provider?.id }?.models.orEmpty()
            val id = selected.model
            val info = catalog.firstOrNull { it.id == id && it.tools != false && it.state.actionable }
            if (provider != null && info != null && provider.availability(info.id).actionable) {
                val reflected = chat.agent.copy(config = chat.agent.config.copy(provider = provider.id, model = info.id))
                NativeReflection(reflected, selection(Effective(reflected, info, provider, chat.profile, chat.memoryCap, chat.userCap)), dependencies.traces, fallback = own)
            } else null
        } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
        return chosen ?: own
    }

    /** Saves what's worth keeping from [entries] of a Session. */
    suspend fun reflect(agent: Agent, sessionId: SessionId, entries: List<SessionEntry>, review: ReflectionRevision? = null): ReflectionResult {
        val turn = reflection.turn(reflected(agent, sessionId).agent, sessionId, entries, reflectionPrompt, review)
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
        if (store.session(agent.id, sessionId).hidden) require(settings == store.session(agent.id, sessionId).settings) { "Hidden Session settings are pinned" }
        val s = settings.copy(model = settings.model?.trim()?.ifEmpty { null }, reasoning = settings.reasoning?.trim()?.ifEmpty { null },
            approvals = settings.approvals?.trim()?.ifEmpty { null }, provider = settings.provider?.trim()?.ifEmpty { null }?.let(::providerId))
        s.provider?.let { requireModelProvider(it); require(s.model != null) { "Choose a model with its provider" } }
        require(s.approvals == null || s.approvals in Tolerance.ids) { "approvals must be one of ${Tolerance.ids.joinToString()}" }
        if (s != store.session(agent.id, sessionId).settings) store.append(agent.id, sessionId, SessionEntry.Settings(s))
        return store.session(agent.id, sessionId)
    }

    /** Renames [sessionId] (blank: back to its first message) or marks it a favorite. */
    suspend fun update(agent: Agent, sessionId: SessionId, title: String?, favorite: Boolean?): SessionInfo {
        val before = store.entries(agent.id, sessionId).filterIsInstance<SessionEntry.Details>().lastOrNull() ?: SessionEntry.Details()
        val details = before.copy(
            title = if (title == null) before.title else title.trim().take(MAX_SESSION_TITLE).ifEmpty { null },
            favorite = favorite ?: before.favorite,
        )
        if (details != before) store.append(agent.id, sessionId, details)
        return store.session(agent.id, sessionId)
    }

    /** `/name rest` activates a user-invocable Skill; returns null when [text] isn't a Skill command. */
    suspend fun slashActivation(agent: Agent, text: String) = skills.slashActivation(agent, text)

    val approvals = ApprovalBroker()
    private val sessionTrust = SessionTrust()
    /** Pending `ask_user` forms; null answers mean skipped. */
    val questions = Broker<Map<String, List<String>>?>()
    val uiCatalogs = SurfaceCatalogs()

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

    /**
     * The Agent as a Session runs it: its settings applied, tool groups this Server lacks dropped, and compaction and
     * Memory limits fitted to the model's context window, and its [profile] resolved once. Capabilities, instructions and runs all start from this.
     */
    private class Effective(val agent: Agent, val info: ModelInfo?, val provider: ModelProvider?, val profile: ModelProfile, val memoryCap: Int, val userCap: Int)

    private suspend fun effective(agent: Agent, sessionId: SessionId?, settings: SessionSettings? = null): Effective {
        val configured = supported(agent.with(settings ?: sessionId?.let { store.session(agent.id, it).settings } ?: SessionSettings()))
            .let { it.copy(config = it.config.copy(provider = providerId(it.config.provider))) }
        val info = modelCatalog().providers.firstOrNull { it.id == configured.config.provider }?.models?.firstOrNull { it.id == configured.config.model }
        val context = info?.context
        val provider = providers[configured.config.provider]
        val base = ModelProfile.of(configured.config.tier ?: inferTier(info ?: ModelInfo(configured.config.model)))
        val profile = (provider?.profile(configured.config.model, base) ?: base).let { it.copy(resultLimits = ResultLimits.of(it.tier, context)) }
        val fitted = if (context == null) configured else configured.copy(config = configured.config.copy(compactAtTokens = minOf(configured.config.compactAtTokens, context * profile.history.compactAtPercent / 100)))
        fun cap(limit: Int) = context?.let { minOf(limit, it * profile.memoryPercent / 100) } ?: limit
        return Effective(fitted, info, provider, profile, cap(memoryCap), cap(userCap))
    }

    /** Which of [tools] the model takes within [scope]: none when it can't call tools, else what its catalog entry, profile and provider allow. */
    private fun usable(e: Effective, tools: List<Tool>, scope: ToolScope = ToolScope.All): Set<String> {
        if (e.profile.tier == ModelTier.NoTools || e.info?.tools == false) return emptySet()
        val listed = tools.filter { t -> e.info?.toolIds?.let { t.identity?.key in it } != false }
        return e.profile.tools.select(listed, scope) { e.provider?.accepts(e.agent.config.model, it.descriptor) != false }.map { it.descriptor.name }.toSet()
    }

    /** [tools] can all be called; the model is sent only [advertised], with catalogued tools behind the catalog's. */
    private class PreparedRun(val effective: Effective, val tools: List<Tool>, val advertised: List<Tool>, val catalog: List<ResolvedTool>, val warnings: List<String>, val chat: CapabilityState)

    private suspend fun prepare(agent: Agent, sessionId: SessionId?, settings: SessionSettings? = null,
        routine: RoutineTurn? = null, taskId: String? = null, activate: Boolean = false, catalogs: List<UiCatalog> = uiCatalogs.forRun(), scope: ToolScope = ToolScope.All, requireModel: Boolean = true): PreparedRun {
        val privateSession = sessionId?.let { store.session(agent.id, it).hidden } == true
        val e = effective(agent, sessionId, settings)
        val chat = if (unmanagedModels) CapabilityState() else e.provider?.availability(e.agent.config.model)
            ?: CapabilityState.unsupported("No model provider '${e.agent.config.provider}'; this model provider is not installed")
        if (activate && requireModel) chat.requireReady()
        val run = RunContext(e.agent, sessionId, routine?.routine, taskId)
        val contributed = (if (privateSession) emptyList() else plugins).map { it.prepare(run, activate).ownedBy(it.instance, it.manifest) }
        val own = PluginTools(if (privateSession) subAgents?.tools(agent.id, requireNotNull(sessionId)).orEmpty() + listOf(AskUserTool()) else toolsFor(e, sessionId, routine, catalogs)).ownedBy(PluginInstanceId("kinuvo.core")).tools
        val aliased = own + contributed.flatMap { it.tools }
        val all = aliased.withModelNames().also { it.indexedTools() }
        val renamed = aliased.zip(all).filter { (a, b) -> a !== b }.associate { (a, b) -> a.descriptor.name to b.descriptor.name }
        val eligible = usable(e, all, scope)
        val descriptors = all.map { it.descriptor.name }.toSet()
        val rules = trustRules.effective(agent.id)
        val catalog = (own.map { ResolvedTool(renamed[it.descriptor.name] ?: it.descriptor.name, it.displayName, identity = it.identity) } +
            contributed.flatMap { it.catalog }.map { it.copy(id = renamed[it.id] ?: it.id) }).distinctBy { (it.identity?.key ?: it.id) to it.target }.map { tool ->
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
        val cataloguedNames = plugins.zip(contributed).filter { it.first.catalogued }.flatMap { it.second.tools }.map { renamed[it.descriptor.name] ?: it.descriptor.name }.toSet()
        val candidates = usableTools.filter { it.descriptor.name in cataloguedNames }.takeIf {
            if (e.profile.tools.catalogExternal) it.size > ToolCatalog.DIRECT_LIMIT else usableTools.size > ToolCatalog.LIMIT
        }.orEmpty()
        // Only when the model can take the catalog's own tools; otherwise every tool stays direct.
        val finders = if (candidates.isEmpty()) emptyList() else PluginTools(ToolCatalog.tools(candidates, e.profile.resultLimits.maxItems)).ownedBy(PluginInstanceId("kinuvo.core")).tools
            .withModelNames(all.map { it.descriptor.name }.toSet()).takeIf { f -> usable(e, f, scope).size == f.size }.orEmpty()
        val hidden = if (finders.isEmpty()) emptyList() else candidates
        val reported = catalog + finders.map { ResolvedTool(it.descriptor.name, it.displayName, identity = it.identity) }
        return PreparedRun(e, usableTools + finders, usableTools - hidden.toSet() + finders, reported, contributed.flatMap { it.warnings }, chat)
    }

    suspend fun capabilities(agent: Agent, sessionId: SessionId?, settings: SessionSettings?, server: ServerCapabilities, revision: Long = 0): SessionCapabilities {
        val prepared = prepare(agent, sessionId, settings)
        val info = prepared.effective.info
        val chat = prepared.chat
        val fileTypes = prepared.effective.provider?.fileTypes(info).orEmpty()
        fun input(kind: String): CapabilityState = when {
            kind != "text" && ServerFeature.Attachments !in server.features -> CapabilityState.unsupported("Attachments are not installed on this Server")
            kind == "file" && fileTypes.isNotEmpty() -> chat
            info?.input == null -> if (kind == "text") chat else CapabilityState.unknown("Model input support is unknown")
            kind !in info.input.orEmpty() -> CapabilityState.unsupported("This model does not accept $kind input")
            else -> chat
        }
        val attachments = ATTACHMENT_MIME_TYPES.associateWith { mime ->
            when {
                ServerFeature.Attachments !in server.features -> CapabilityState.unsupported("Attachments are not installed on this Server")
                attachmentInput(mime) != "file" -> input(attachmentInput(mime))
                mime in fileTypes -> chat
                else -> CapabilityState.unsupported("This file type isn't supported by this provider and model")
            }
        }
        return SessionCapabilities(revision, server, chat, listOf("text", "image", "file", "audio").associateWith(::input), prepared.catalog, attachments)
    }

    /**
     * Builds a runner with the Agent's current tools, starting what its plugins need on first use. [sessionId] is the
     * Session it runs in; [routine] is set for a Routine's run, which follows the Routine's approval mode.
     */
    suspend fun runner(agent: Agent, sessionId: SessionId? = null, routine: RoutineTurn? = null, taskId: String? = null, catalogs: List<UiCatalog> = uiCatalogs.forRun(),
        scope: ToolScope = ToolScope.All, reports: ToolReports? = null): AgentRunner =
        native.runner(compose(agent, sessionId, routine, taskId, catalogs, scope, reports))

    suspend fun compose(agent: Agent, sessionId: SessionId? = null, routine: RoutineTurn? = null, taskId: String? = null, catalogs: List<UiCatalog> = uiCatalogs.forRun(),
        scope: ToolScope = ToolScope.All, reports: ToolReports? = null, requireModel: Boolean = true): NativeRun {
        if (sessionId != null && store.session(agent.id, sessionId).hidden) requireOnDevice(agent, sessionId)
        val prepared = prepare(agent, sessionId, routine = routine, taskId = taskId, activate = true, catalogs = catalogs, scope = scope, requireModel = requireModel)
        val e = prepared.effective
        val agent = e.agent
        val tools = prepared.tools.map(secrets::redacting)
        val advertised = prepared.advertised.map { it.descriptor.name }.toSet().let { names -> tools.filter { it.descriptor.name in names } }
        val sections = plugins.mapNotNull { it.instructions(agent) }
        val today = "${Clock.System.todayIn(timeZone)} ($timeZone)"
        val context = if (e.profile.prompt != PromptStyle.Full) basicContextFor(agent, docs, advertised, today, identity = e.profile.prompt == PromptStyle.BasicWithIdentity)
        else personalContextFor(agent, docs) + "\n" + capabilitiesFor(agent, advertised) + "\nServer platform: $platformDescription. Today is ${Clock.System.todayIn(timeZone)} ($timeZone).\n" + sections.joinToString("\n\n") { "## ${it.title}\n\n${it.body}" } +
            if (advertised.any { it.descriptor.name == ToolNames.ACTIVATE_SKILL }) "\n\nCurrent Skills:\n" + skillCatalogFor(skills.load(agent)) else ""
        val service = ToolService(
            tools = tools,
            advertisedTools = advertised.mapNotNull { it.descriptor.simplified(e.profile.tools.schema) },
            approvals = sessionId?.let(sessionTrust::scope).let { session ->
                when (routine?.routine?.approvals) {
                    null -> Approvals(ApprovalPolicy(agent, trustRules, session = session, scope = scope), approvals)
                    RoutineApprovals.Auto -> Approvals(ApprovalPolicy(agent, trustRules, Tolerance.Full, session, scope), approvals)
                    RoutineApprovals.Deny -> Approvals(ApprovalPolicy(agent, trustRules, routineTolerance(agent), session, scope), approvals, ApprovalDecision.Deny(UNATTENDED_DENIAL))
                    RoutineApprovals.Inherit -> Approvals(ApprovalPolicy(agent, trustRules, routineTolerance(agent), session, scope), approvals, wait = routineApprovalWait, skipped = { routine.skipped += it })
                }
            },
            questions = questions,
            attach = { name, bytes -> attachments.upload(UploadAttachmentParams(name, Base64.encode(bytes))) },
            maxItems = e.profile.resultLimits.maxItems,
            callsPerTurn = e.profile.tools.callsPerTurn,
            reports = reports,
            consume = { sessionId?.let { subAgents?.consume(it) } },
        )
        return NativeRun(
            agent = agent,
            store = store,
            model = selection(e),
            tools = service,
            gate = service,
            contextTokens = e.info?.context,
            traces = dependencies.traces,
            context = secrets.redact(context),
            warnings = prepared.warnings.map { secrets.redact(it) },
            routine = routine,
            profile = e.profile,
        )
    }

    /** Routines run Relaxed, so only High-risk calls wait or are denied, unless the Agent never asks. */
    private fun routineTolerance(agent: Agent) = if (Tolerance.of(agent.config.approvals) == Tolerance.Full) Tolerance.Full else Tolerance.Relaxed

    /** A file from [agent]'s Workspace, e.g. to copy into an Artifact. */
    suspend fun readFile(agent: Agent, path: String): String =
        plugins.firstNotNullOfOrNull { it.readFile(agent, path) } ?: error("${agent.id} has no Workspace")

    /** The MCP App that [request]'s stored tool result shows; clients never name the app themselves. */
    private suspend fun app(request: McpAppRequest): Pair<McpApps, McpAppRef> {
        val ref = store.entries(request.agentId, request.sessionId).filterIsInstance<SessionEntry.ToolResult>()
            .lastOrNull { it.id == request.callId }?.app ?: error("No MCP App for this call")
        return (plugins.firstNotNullOfOrNull { it.apps } ?: error("MCP Apps are unavailable")) to ref
    }

    suspend fun appResource(request: McpAppRequest): McpAppResource = app(request).let { (apps, ref) -> apps.resource(request.agentId, ref) }

    /** Runs a tool of the app's own MCP server for the app, through the tool service: the Session's Approvals, asked in its Session. */
    suspend fun appCallTool(agent: Agent, request: McpAppToolRequest): JsonObject {
        val (apps, ref) = app(request.app)
        val sessionId = request.app.sessionId
        val asked = secrets.redacting(apps.tool(agent.id, ref, request.name))
        // Checked again once the Approval is answered: the server or tool may have been removed meanwhile.
        val tool = object : Tool by asked {
            override suspend fun executeResult(args: JsonObject, callId: String, run: ToolRun) =
                secrets.redacting(apps.tool(agent.id, ref, request.name)).executeResult(args, callId, run)
        }
        val policy = ApprovalPolicy(agent.with(store.session(agent.id, sessionId).settings), trustRules, session = sessionTrust.scope(sessionId))
        val service = ToolService(listOf(tool), approvals = Approvals(policy, approvals, wait = APP_APPROVAL_WAIT))
        val call = SessionEntry.ToolCall(ToolCallId("app-${request.app.callId}-${Uuid.random()}"), tool.descriptor.name, request.arguments.toString(), tool.identity)
        val result = withAppApproval(agent, sessionId, request.name) { turn -> service.invoke(call, turn) }
        return result.appResult ?: buildJsonObject {
            put("content", buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", result.output) }) })
            put("isError", result.isError)
        }
    }

    /** Shows only the Approval a call needs, in its Session and one at a time: a Session shows a single pending Approval. */
    private suspend fun <T> withAppApproval(agent: Agent, sessionId: SessionId, name: String, call: suspend (ToolTurn) -> T): T {
        val lock = appApprovalLock(sessionId)
        var pending: InteractionId? = null
        val turn = ToolTurn(emit = { event ->
            when (event) {
                is AgentEvent.InteractionRequested -> {
                    lock.lock()
                    try {
                        check(log.status(sessionId) == SessionStatus.Idle) { "The Agent is busy; try '$name' again when it finishes" }
                        log.publish(agent.id, sessionId, event)
                        pending = event.interaction.id
                    } catch (e: Throwable) {
                        lock.unlock()
                        throw e
                    }
                }
                is AgentEvent.InteractionResolved -> if (pending != null) {
                    pending = null
                    try { log.publish(agent.id, sessionId, event) } finally { lock.unlock() }
                }
                else -> Unit
            }
        })
        try {
            return call(turn)
        } finally {
            pending?.let { id ->
                withContext(NonCancellable) {
                    try { log.publish(agent.id, sessionId, AgentEvent.InteractionResolved(id, false)) } finally { lock.unlock() }
                }
            }
        }
    }

    private val appApprovals = Mutex()
    private val appApprovalLocks = mutableMapOf<String, Mutex>()
    private suspend fun appApprovalLock(sessionId: SessionId) = appApprovals.withLock { appApprovalLocks.getOrPut(sessionId.value) { Mutex() } }

    /** Stops everything the plugins run, e.g. before the process exits. */
    suspend fun close() { computers.close(); try { native.close() } finally { dependencies.resources.close() } }

    suspend fun requireOnDevice(agent: Agent, sessionId: SessionId? = null) {
        val e = effective(agent, sessionId)
        if (!(unmanagedModels && e.info == null) && e.info?.location != ExecutionLocation.ServerHost)
            throw ServerException(SubAgentError.Privacy)
    }

    /** Stops what the plugins run for the Agent, e.g. after its exec settings changed. */
    suspend fun release(agent: Agent) = dependencies.resources.release(agent.id)

    private suspend fun toolsFor(e: Effective, sessionId: SessionId?, routine: RoutineTurn?, catalogs: List<UiCatalog>): List<Tool> {
        val agent = e.agent
        val search = if (ToolNames.WEB_FETCH in agent.config.tools) webSearch() else null
        val available = modelCatalog().imageModels().filter { ref ->
            val candidate = providers[ref.provider]
            candidate?.images != null && candidate.availability(ref.model).actionable
        }
        val selected = resolveImageModel(ProviderModelRef(agent.config.provider, agent.config.model), imageModel, available)
        val images = selected?.let { providers[it.provider]?.images }
        val imageTool = if (selected != null && images != null && sessionId != null) GenerateImageTool(images, selected.model, {
                store.entries(agent.id, sessionId).flatMap { entry -> when (entry) {
                    is SessionEntry.UserMessage -> entry.attachments
                    is SessionEntry.ToolResult -> entry.attachments
                    else -> emptyList()
                } }
            }, attachments::bytes) else null
        val uiTool = catalogs.takeIf { sessionId != null && it.isNotEmpty() }?.let(::ShowUiTool)
        return (sessionId?.let { subAgents?.tools(agent.id, it) }.orEmpty()) + builtInTools(e, sessionId, routine, search) + listOfNotNull(search?.let { WebSearchTool(it, compact = e.profile.tools.callsPerTurn != null, region = SearchRegion.fromTimeZone(timeZone.id)) }, imageTool, uiTool, uiTool?.let { ShowHtmlTool() })
    }

    private suspend fun builtInTools(e: Effective, sessionId: SessionId?, routine: RoutineTurn?, search: WebSearch?): List<Tool> = e.agent.let { agent -> agent.config.tools.mapNotNull {
        when (it) {
            ToolNames.MEMORY -> MemoryTool(agent, docs, memoryCap = e.memoryCap, userCap = e.userCap)
            ToolNames.WEB_FETCH -> dependencies.webFetch(search)
            ToolNames.SCHEDULE -> ScheduleTool(agent, routines, { timeZone }, sessionId, routine?.routine)
            ToolNames.ASK_USER -> AskUserTool(routine != null).takeIf { routine?.routine?.approvals != RoutineApprovals.Deny }
            ToolNames.ARTIFACT -> ArtifactTool(agent, sessionId, artifacts) { readFile(agent, it) }
            else -> null
        }
    } + if (ToolNames.SKILLS in agent.config.tools) skillTools(agent) else emptyList() } + listOfNotNull(routine?.let(::RoutineReportTool))

    /** [agent] without the tool groups this Server lacks the feature for, so neither its tools nor their instructions show. */
    private fun supported(agent: Agent) = agent.copy(config = agent.config.copy(tools = agent.config.tools.flatMap { name ->
        Toolsets.legacyGroups(name)
    }.distinct().filter { it in toolGroups }))

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
        const val UNATTENDED_DENIAL = "This is an unattended Routine run, so tool calls that need an Approval are denied."
        val APP_APPROVAL_WAIT = 10.minutes
    }
}
