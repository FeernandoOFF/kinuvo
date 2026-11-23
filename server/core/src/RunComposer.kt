package kinuvo.server

import kinuvo.harness.Agent
import kinuvo.harness.AgentDoc
import kinuvo.server.plugin.PluginTools
import kinuvo.server.plugin.RunContext
import kinuvo.server.plugin.instance
import kinuvo.server.plugin.ownedBy
import kinuvo.server.plugin.withModelNames
import kinuvo.server.devices.*
import kinuvo.server.screens.*
import kinuvo.server.plugin.*
import kinuvo.protocol.session.ATTACHMENT_MIME_TYPES
import kinuvo.protocol.session.attachmentInput

import kinuvo.harness.AgentRunner
import kinuvo.harness.ModelProfile
import kinuvo.harness.ModelSelection
import kinuvo.harness.NativeHarnessHost
import kinuvo.harness.NativeReflection
import kinuvo.harness.NativeRun
import kinuvo.harness.PromptStyle
import kinuvo.harness.ResultLimits
import kinuvo.harness.SessionStorage
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
import kinuvo.server.knowledge.Reflection
import kotlinx.datetime.todayIn
import kotlin.time.Clock
import kotlin.time.Duration
import kinuvo.protocol.session.SessionEntry
import kinuvo.harness.simplified
import kinuvo.harness.with
import kinuvo.server.tools.WorkflowTool
import kinuvo.server.tools.AgentManageTools
import kinuvo.server.tools.AgentManageScope
import kinuvo.server.tools.ToolScope
import kinuvo.server.tools.select
import kinuvo.protocol.model.ModelTier
import kotlinx.serialization.json.*
import kinuvo.server.artifacts.ArtifactTool
import kinuvo.server.tools.AskUserTool
import kinuvo.server.tools.MemoryTool
import kinuvo.server.plugin.Tool
import kinuvo.server.plugin.indexedTools
import kinuvo.server.tools.Toolsets
import kinuvo.harness.ToolReports
import kinuvo.server.tools.ToolService
import kinuvo.harness.ExecutionInput
import kinuvo.harness.RoutineTurn
import kinuvo.harness.RunKind
import kinuvo.protocol.tool.ToolNames
import kinuvo.server.tools.ToolCatalog
import kinuvo.server.plugin.WebSearch
import kinuvo.server.plugin.SearchRegion
import kinuvo.server.tools.WebSearchTool
import kinuvo.protocol.approval.ApprovalDecision
import kinuvo.protocol.routine.RoutineApprovals
import kinuvo.protocol.session.SessionSettings
import kinuvo.protocol.capability.ExecutionLocation
import kinuvo.protocol.capability.ServerFeature
import kinuvo.protocol.capability.Integration
import kinuvo.protocol.capability.CapabilityState
import kinuvo.protocol.capability.Availability
import kinuvo.protocol.capability.Support
import kinuvo.protocol.capability.Authorization
import kinuvo.protocol.capability.ResolvedTool
import kinuvo.protocol.capability.SessionCapabilities
import kinuvo.protocol.capability.ServerCapabilities
import kinuvo.protocol.model.ProviderModelRef
import kinuvo.protocol.model.imageModels
import kinuvo.protocol.model.resolveImageModel
import kinuvo.protocol.model.ModelInfo
import kinuvo.protocol.plugin.PluginInstanceId
import kinuvo.protocol.session.UploadAttachmentParams
import kinuvo.protocol.agent.AgentId
import kinuvo.protocol.session.SessionId
import kotlin.io.encoding.Base64
import kotlinx.coroutines.CancellationException
import kinuvo.server.providers.ModelProvider

private class ScratchStorage : SessionStorage {
    private val entries = mutableListOf<SessionEntry>()
    override suspend fun entries(agentId: AgentId, sessionId: SessionId) = entries.toList()
    override suspend fun append(agentId: AgentId, sessionId: SessionId, entry: SessionEntry) { entries += entry }
}

/** Composes what a run needs from the Agent, its Session and the Server's tools, models and instructions: the built-in harness's host. */
class RunComposer internal constructor(
    private val harness: AgentRuntime,
    private val dependencies: RuntimeDependencies,
    private val platformDescription: String,
    private val routineApprovalWait: Duration,
    private val unmanagedModels: Boolean,
) : NativeHarnessHost {
    private val store get() = harness.store
    private val docs get() = harness.docs
    private val skills get() = harness.skills
    private val trustRules get() = harness.trustRules
    private val secrets get() = harness.secrets
    private val attachments get() = harness.attachments
    private val artifacts get() = harness.artifacts
    private val routines get() = harness.routines
    private val features get() = harness.features
    private val plugins get() = harness.plugins
    private val providers get() = harness.providers
    private val approvals get() = harness.approvals
    private val sessionTrust get() = harness.sessionTrust
    private val questions get() = harness.questions
    private val uiCatalogs get() = harness.uiCatalogs
    private val subAgents get() = harness.subAgents
    private val teams get() = harness.teams
    private val agentManagement get() = harness.agentManagement
    private val timeZone get() = harness.timeZone
    private val imageModel get() = harness.imageModel
    private val reflectionModel get() = harness.reflectionModel
    private val reflectionPrompt get() = harness.reflectionPrompt
    private val memoryCap get() = harness.memoryCap
    private val userCap get() = harness.userCap
    private val native get() = harness.native
    private suspend fun modelCatalog() = harness.modelCatalog()
    private fun providerId(id: String) = harness.providerId(id)
    private suspend fun readFile(agent: Agent, path: String) = harness.readFile(agent, path)

    /** [agent] as a Session's Reflection reads it: with the Session's own settings. */
    suspend fun reflectedAgent(agent: Agent, sessionId: SessionId): Agent = reflected(agent, sessionId).agent

    private fun selection(e: Effective) = ModelSelection(e.agent.config, e.info)

    override suspend fun run(agentId: AgentId, sessionId: SessionId, input: ExecutionInput): NativeRun {
        val agent = store.agents().firstOrNull { it.id == agentId } ?: throw ServerException(AgentError.NotFound(agentId))
        return when (val kind = input.kind) {
            is RunKind.Chat -> compose(agent, sessionId, taskId = kind.taskId, catalogs = kind.catalogs ?: uiCatalogs.forRun(), reports = kind.reports, discovery = kind.discovery && kind.report == null)
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

    /**
     * The Agent as a Session runs it: its settings applied, tool groups this Server lacks dropped, and compaction and
     * Memory limits fitted to the model's context window, and its [profile] resolved once. Capabilities, instructions and runs all start from this.
     */
    private class Effective(val agent: Agent, val info: ModelInfo?, val provider: ModelProvider?, val profile: ModelProfile, val memoryCap: Int, val userCap: Int)

    private suspend fun effective(agent: Agent, sessionId: SessionId?, settings: SessionSettings? = null, requireModel: Boolean = true): Effective {
        val configured = supported(agent.with(settings ?: sessionId?.let { store.session(agent.id, it).settings } ?: SessionSettings()))
            .let { it.copy(config = it.config.copy(provider = providerId(it.config.provider))) }
        if (!requireModel) return Effective(configured, null, null, ModelProfile.of(ModelTier.Frontier), memoryCap, userCap)
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
        val e = effective(agent, sessionId, settings, requireModel)
        val chat = if (unmanagedModels) CapabilityState() else e.provider?.availability(e.agent.config.model)
            ?: CapabilityState.unsupported("No model provider '${e.agent.config.provider}'; this model provider is not installed")
        if (activate && requireModel) chat.requireReady()
        val run = RunContext(e.agent, sessionId, routine?.routine, taskId)
        val contributed = (if (privateSession) emptyList() else plugins).map { it.prepare(run, activate).ownedBy(it.instance, it.manifest) }
        val own = PluginTools(if (privateSession) subAgents?.tools(agent.id, requireNotNull(sessionId)).orEmpty() + manageTools(agent, sessionId, true) + listOf(AskUserTool()) else toolsFor(e, sessionId, routine, catalogs, scope)).ownedBy(PluginInstanceId("kinuvo.core")).tools
        val aliased = (own + contributed.flatMap { it.tools }).filter { harness.allowedTools?.contains(it.identity?.localId ?: it.descriptor.name) != false }
        val all = aliased.withModelNames().also { it.indexedTools() }
        val renamed = aliased.zip(all).filter { (a, b) -> a !== b }.associate { (a, b) -> a.descriptor.name to b.descriptor.name }
        val eligible = if (requireModel) usable(e, all, scope) else all.filter(scope::allows).map { it.descriptor.name }.toSet()
        val descriptors = all.map { it.descriptor.name }.toSet()
        val rules = trustRules.effective(agent.id)
        val catalog = (own.map { ResolvedTool(renamed[it.descriptor.name] ?: it.descriptor.name, it.displayName, identity = it.identity) } +
            contributed.flatMap { it.catalog }.map { it.copy(id = renamed[it.id] ?: it.id) }).distinctBy { (it.identity?.key ?: it.id) to it.target }.map { tool ->
            val state = when {
                e.info?.tools == false || e.info?.toolIds?.let { tool.identity?.localId != "*" && tool.identity?.key !in it } == true ||
                    tool.id in descriptors && tool.id !in eligible -> if (tool.state.reasonCode == "needs_setup") tool.state.copy(support = Support.Unsupported) else CapabilityState.unsupported("This model cannot call ${tool.name}")
                tool.state.reasonCode != "needs_setup" && rules.deny.blockedTool(tool.identity) != null -> tool.state.copy(authorization = Authorization.Denied, reason = "Blocked by a Deny rule", reasonCode = "policy")
                requireModel && e.info?.tools == null -> tool.state.copy(support = Support.Unknown, reason = tool.state.reason ?: "Model tool support is unknown")
                else -> tool.state
            }
            tool.copy(state = state)
        }.filter { harness.allowedTools?.contains(it.identity?.localId ?: it.id) != false }
        val usableTools = all.filter { tool -> tool.descriptor.name in eligible && catalog.none { it.id == tool.descriptor.name && it.state.reasonCode == "needs_setup" } }
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

    internal suspend fun externalTools(agent: Agent, sessionId: SessionId?) =
        prepare(agent, sessionId, requireModel = false).catalog

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
        return SessionCapabilities(revision, server, chat, listOf("text", "image", "file", "audio").associateWith(::input), prepared.catalog, attachments, queue = true, steer = true)
    }

    /**
     * Builds a runner with the Agent's current tools, starting what its plugins need on first use. [sessionId] is the
     * Session it runs in; [routine] is set for a Routine's run, which follows the Routine's approval mode.
     */
    suspend fun runner(agent: Agent, sessionId: SessionId? = null, routine: RoutineTurn? = null, taskId: String? = null, catalogs: List<UiCatalog> = uiCatalogs.forRun(),
        scope: ToolScope = ToolScope.All, reports: ToolReports? = null): AgentRunner =
        native.runner(compose(agent, sessionId, routine, taskId, catalogs, scope, reports))

    suspend fun compose(agent: Agent, sessionId: SessionId? = null, routine: RoutineTurn? = null, taskId: String? = null, catalogs: List<UiCatalog> = uiCatalogs.forRun(),
        scope: ToolScope = ToolScope.All, reports: ToolReports? = null, requireModel: Boolean = true, discovery: Boolean = false): NativeRun {
        if (sessionId != null && store.session(agent.id, sessionId).hidden) requireOnDevice(agent, sessionId)
        val prepared = prepare(agent, sessionId, routine = routine, taskId = taskId, activate = true, catalogs = catalogs, scope = scope, requireModel = requireModel)
        val e = prepared.effective
        val agent = e.agent
        val tools = prepared.tools.map(secrets::redacting)
        val advertised = prepared.advertised.map { it.descriptor.name }.toSet().let { names -> tools.filter { it.descriptor.name in names } }
        val sections = plugins.filter { plugin ->
            plugin.definition.id !in setOf("kinuvo.browser", "kinuvo.computer") || prepared.tools.any { it.identity?.instance == plugin.instance }
        }.mapNotNull { it.instructions(agent) }
        val today = "${Clock.System.todayIn(timeZone)} ($timeZone)"
        val style = if (requireModel) e.profile.prompt else PromptStyle.Full
        val memory = if (style == PromptStyle.Full && requireModel) docs.read(agent.id, AgentDoc.Memory) else ""
        val userNotes = if (style == PromptStyle.Full && requireModel) docs.read(agent.id, AgentDoc.UserNotes) else ""
        val firstSession = e.profile.tier == ModelTier.Frontier && discovery && requireModel && routine == null && taskId == null && sessionId != null &&
            store.session(agent.id, sessionId).let { !it.hidden && it.parent == null && it.routine == null && it.teamId == null } &&
            memory.isBlank() && userNotes.isBlank() &&
            // The current user message is appended by the runner after composition.
            store.entries(agent.id, sessionId).all {
                it is SessionEntry.Started || it is SessionEntry.Settings || it is SessionEntry.Details || it is SessionEntry.Activation
            }
        val context = when (style) {
            PromptStyle.Basic, PromptStyle.BasicWithIdentity -> basicContextFor(agent, docs, advertised, today, identity = style == PromptStyle.BasicWithIdentity)
            PromptStyle.Compact, PromptStyle.Full -> {
                val personal = if (style == PromptStyle.Compact) compactContextFor(agent, docs)
                    else (if (requireModel) personalContextFor(memory, userNotes) else instructionsFor(agent, docs)) + "\n$PROACTIVITY_BRIEF\n\n" + aboutKinuvoFor(advertised, discovery = firstSession)
                personal + "\n" + (if (firstSession) firstSessionFor(advertised) else "") + capabilitiesFor(agent, advertised, prepared.catalog, compact = style == PromptStyle.Compact) +
                    "\nServer platform: $platformDescription. Today is $today.\n" +
                    sections.joinToString("\n\n") { "## ${it.title}\n\n${it.body}" } +
                    if (advertised.any { it.descriptor.name == ToolNames.ACTIVATE_SKILL }) "\n\nCurrent Skills:\n" + skillCatalogFor(skills.load(agent)) else ""
            }
        }
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

    /** One model request of [agent], or of [model] instead, with [system] as its only context, no tools and a throwaway transcript; nothing of the Agent's own context is sent. */
    suspend fun completion(agent: Agent, system: String?, model: ProviderModelRef?): NativeRun {
        val e = effective(agent, null, model?.let { SessionSettings(model = it.model, provider = it.provider) })
        (if (unmanagedModels) CapabilityState() else e.provider?.availability(e.agent.config.model)
            ?: CapabilityState.unsupported("No model provider '${e.agent.config.provider}'; this model provider is not installed")).requireReady()
        val none = ToolService(emptyList())
        return NativeRun(e.agent, ScratchStorage(), selection(e), none, none, context = secrets.redact(system.orEmpty()), contextTokens = e.info?.context, profile = e.profile)
    }

    suspend fun autoApproveHarness(agent: Agent, sessionId: SessionId, kind: RunKind): Boolean {
        val configured = agent.with(store.session(agent.id, sessionId).settings)
        val routine = (kind as? RunKind.Routine)?.turn
        return if (routine == null) Tolerance.of(configured.config.approvals) == Tolerance.Full
        else routine.routine.approvals == RoutineApprovals.Auto || routineTolerance(configured) == Tolerance.Full
    }

    /** Routines run Relaxed, so only High-risk calls wait or are denied, unless the Agent never asks. */
    private fun routineTolerance(agent: Agent) = if (Tolerance.of(agent.config.approvals) == Tolerance.Full) Tolerance.Full else Tolerance.Relaxed

    suspend fun requireOnDevice(agent: Agent, sessionId: SessionId? = null) {
        val e = effective(agent, sessionId)
        if (!(unmanagedModels && e.info == null) && e.info?.location != ExecutionLocation.ServerHost)
            throw ServerException(SubAgentError.Privacy(agent.name))
    }

    private suspend fun toolsFor(e: Effective, sessionId: SessionId?, routine: RoutineTurn?, catalogs: List<UiCatalog>, scope: ToolScope = ToolScope.All): List<Tool> {
        val agent = e.agent
        val search = if (ToolNames.WEB_FETCH in agent.config.tools) harness.webSearch() else null
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
        return manageTools(agent, sessionId) + (sessionId?.let { teams?.tools(agent.id, it) }.orEmpty()) + (sessionId?.let { subAgents?.tools(agent.id, it) }.orEmpty()) + builtInTools(e, sessionId, routine, search) + listOfNotNull(search?.let { WebSearchTool(it, compact = e.profile.tools.callsPerTurn != null, region = SearchRegion.fromTimeZone(timeZone.id)) }, imageTool, uiTool, ShowHtmlTool().takeIf { sessionId != null && (uiTool != null || ToolNames.SHOW_HTML in scope.names.orEmpty()) })
    }

    private suspend fun manageTools(agent: Agent, session: SessionId?, hidden: Boolean = false): List<Tool> =
        if (ToolNames.AGENT_MANAGE !in agent.config.tools || store.agents().none { it.id == agent.id }) emptyList()
        else agentManagement?.let { AgentManageTools(it).tools(AgentManageScope(agent.id, session), hidden) }.orEmpty()

    private suspend fun builtInTools(e: Effective, sessionId: SessionId?, routine: RoutineTurn?, search: WebSearch?): List<Tool> = e.agent.let { agent -> agent.config.tools.mapNotNull {
        when (it) {
            ToolNames.MEMORY -> MemoryTool(agent, docs, memoryCap = e.memoryCap, userCap = e.userCap)
            ToolNames.WEB_FETCH -> dependencies.webFetch(search)
            ToolNames.WORKFLOWS -> harness.workflows?.let { WorkflowTool(it, agent.id, sessionId) }
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
    internal val toolGroups get() = ToolNames.OPTIONAL.filter { it !in ToolNames.FROM_PLUGINS && TOOL_FEATURES[it]?.let(features::contains) != false }.toSet() +
        dependencies.settings.integrations[Integration.Tools].orEmpty()

    private fun skillTools(agent: Agent): List<Tool> =
        listOf(ActivateSkillTool(agent, skills), ReadSkillFileTool(agent, skills), SkillManageTool(agent, skills))

    private companion object {
        val TOOL_FEATURES = mapOf(ToolNames.SKILLS to ServerFeature.Skills, ToolNames.SCHEDULE to ServerFeature.Routines, ToolNames.ARTIFACT to ServerFeature.Artifacts,
            ToolNames.KNOWLEDGE to ServerFeature.Knowledge, ToolNames.TASKS to ServerFeature.Tasks, ToolNames.DEVICE_TOOLS to ServerFeature.DeviceTools,
            ToolNames.COMPUTER to ServerFeature.Computer)
        const val UNATTENDED_DENIAL = "This is an unattended Routine run, so tool calls that need an Approval are denied."
    }
}
