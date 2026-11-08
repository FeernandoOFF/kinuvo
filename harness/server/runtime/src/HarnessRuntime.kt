package kinuvo.harness.server

import kinuvo.harness.server.devices.*
import kinuvo.harness.server.screens.*
import kinuvo.harness.core.plugin.*
import kinuvo.protocol.ATTACHMENT_MIME_TYPES
import kinuvo.protocol.attachmentInput

import kinuvo.harness.core.*
import kinuvo.harness.core.tools.GenerateImageTool
import kinuvo.harness.core.tools.ShowUiTool
import kinuvo.harness.core.tools.ShowHtmlTool
import kinuvo.protocol.UiCatalog
import kinuvo.harness.core.routines.RoutineReportTool
import kinuvo.harness.core.routines.RoutineRun
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
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid
import kinuvo.protocol.AgentEvent
import kotlinx.coroutines.NonCancellable
import kinuvo.protocol.SessionStatus
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kinuvo.protocol.ReflectionResult
import kinuvo.protocol.SessionEntry
import kinuvo.protocol.McpAppRef
import kinuvo.protocol.McpAppRequest
import kinuvo.protocol.McpAppResource
import kinuvo.protocol.McpAppToolRequest
import kinuvo.harness.core.plugin.McpApps
import kinuvo.harness.core.tools.ToolRun
import kinuvo.harness.core.tools.ToolGroup
import kinuvo.harness.core.tools.simplified
import kinuvo.harness.core.tools.ToolScope
import kinuvo.harness.core.tools.select
import kinuvo.protocol.ModelTier
import ai.koog.agents.core.tools.ToolDescriptor
import kotlinx.serialization.json.*
import kinuvo.harness.core.store.Store
import kinuvo.harness.core.artifacts.ArtifactTool
import kinuvo.harness.core.tools.AskUserTool
import kinuvo.harness.core.tools.MemoryTool
import kinuvo.harness.core.tools.Tool
import kinuvo.harness.core.tools.indexedTools
import kinuvo.harness.core.tools.Toolsets
import kinuvo.harness.core.tools.ToolNames
import kinuvo.harness.core.tools.ToolCatalog
import kinuvo.harness.core.effective
import kinuvo.harness.core.tools.WebSearch
import kinuvo.harness.core.tools.WebSearchTool
import kinuvo.protocol.ApprovalDecision
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
import kinuvo.protocol.PluginInstanceId
import kinuvo.protocol.UploadAttachmentParams
import kotlin.io.encoding.Base64
import kotlinx.coroutines.CancellationException

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
    /** How long an unattended Routine run waits for an Approval before skipping the call. */
    private val routineApprovalWait: Duration = 30.minutes,
) : ServerSettings by dependencies.settings {
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
    private val reflection = Reflection(knowledge, docs, skills, { trustRules.effective(it.id).deny }, tracing = dependencies.tracing) { Clock.System.todayIn(timeZone) }
    private val providers = dependencies.modelProviders.associateBy { it.id }
    suspend fun modelCatalog(refresh: Boolean = false) = dependencies.modelCatalog(refresh)
    val modelProviders get() = providers.values.toList()
    fun requireModelProvider(id: String) { require(providerId(id) in providers) { "No model provider '$id'" } }
    /** A custom provider's bare ID, as agent.yaml named it before custom IDs were prefixed, still finds it. */
    fun providerId(id: String) = if (id !in providers && CUSTOM_PROVIDERS + id in providers) CUSTOM_PROVIDERS + id else id
    val usage: StateFlow<Usage?> = dependencies.usage
    private val modelFactoryOverride = modelFactory != null
    private fun model(e: Effective): AgentModel = modelFactory?.invoke(e.agent.config)
        ?: (e.provider ?: error("No model provider '${e.agent.config.provider}'")).model(e.agent.config, e.info)

    /** Whether conversations with [agent] are reflected on afterwards. */
    fun reflects(agent: Agent) = ServerFeature.Knowledge in features && agent.config.tools.any { it in setOf(ToolNames.KNOWLEDGE, ToolNames.MEMORY, ToolNames.SKILLS) } && reflectionEnabled

    /** Saves what's worth keeping from [entries] of a Session. */
    suspend fun reflect(agent: Agent, sessionId: String, entries: List<SessionEntry>, review: ReflectionRevision? = null): ReflectionResult {
        val resolved = try { agent.with(store.session(agent.id, sessionId).settings) }
        catch (e: CancellationException) { throw e } catch (_: Exception) { agent }
        val chat = effective(resolved, null)
        val chosen = try {
            val selected = reflectionModel
            val provider = providers[providerId(selected.provider ?: chat.agent.config.provider)]
            val catalog = modelCatalog().providers.firstOrNull { it.id == provider?.id }?.models.orEmpty()
            val id = selected.model
            val info = catalog.firstOrNull { it.id == id && it.tools != false && it.state.actionable }
            if (provider != null && info != null && provider.availability(info.id).actionable) {
                val reflected = chat.agent.copy(config = chat.agent.config.copy(provider = provider.id, model = info.id))
                reflected to model(Effective(reflected, info, provider, chat.profile, chat.memoryCap, chat.userCap))
            } else null
        } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
        val (reflected, model) = chosen ?: (chat.agent to model(chat))
        return reflection.reflect(reflected, model, sessionId, entries, reflectionPrompt, review)
    }

    suspend fun newSession(agent: Agent, routine: RoutineRef? = null): SessionInfo {
        return store.createSession(agent.id, instructionsFor(agent, docs), routine)
    }

    /** Replaces [sessionId]'s own settings; its next run uses them. */
    suspend fun configure(agent: Agent, sessionId: String, settings: SessionSettings): SessionInfo {
        val s = settings.copy(model = settings.model?.trim()?.ifEmpty { null }, reasoning = settings.reasoning?.trim()?.ifEmpty { null },
            approvals = settings.approvals?.trim()?.ifEmpty { null }, provider = settings.provider?.trim()?.ifEmpty { null }?.let(::providerId))
        s.provider?.let { requireModelProvider(it); require(s.model != null) { "Choose a model with its provider" } }
        require(s.approvals == null || s.approvals in Tolerance.ids) { "approvals must be one of ${Tolerance.ids.joinToString()}" }
        if (s != store.session(agent.id, sessionId).settings) store.append(agent.id, sessionId, SessionEntry.Settings(s))
        return store.session(agent.id, sessionId)
    }

    /** Renames [sessionId] (blank: back to its first message) or marks it a favorite. */
    suspend fun update(agent: Agent, sessionId: String, title: String?, favorite: Boolean?): SessionInfo {
        val before = store.entries(agent.id, sessionId).filterIsInstance<SessionEntry.Details>().lastOrNull() ?: SessionEntry.Details()
        val details = SessionEntry.Details(
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

    /**
     * The Agent as a Session runs it: its settings applied, tool groups this Server lacks dropped, and compaction and
     * Memory limits fitted to the model's context window, and its [profile] resolved once. Capabilities, instructions and runs all start from this.
     */
    private class Effective(val agent: Agent, val info: ModelInfo?, val provider: ModelProvider?, val profile: ModelProfile, val memoryCap: Int, val userCap: Int)

    private suspend fun effective(agent: Agent, sessionId: String?, settings: SessionSettings? = null): Effective {
        val configured = supported(agent.with(settings ?: sessionId?.let { store.session(agent.id, it).settings } ?: SessionSettings()))
            .let { it.copy(config = it.config.copy(provider = providerId(it.config.provider))) }
        val info = modelCatalog().providers.firstOrNull { it.id == configured.config.provider }?.models?.firstOrNull { it.id == configured.config.model }
        val context = info?.context
        val provider = providers[configured.config.provider]
        val base = ModelProfile.of(configured.config.tier ?: inferTier(info ?: ModelInfo(configured.config.model)))
        val profile = provider?.profile(configured.config.model, base) ?: base
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

    private suspend fun prepare(agent: Agent, sessionId: String?, settings: SessionSettings? = null,
        routine: RoutineRun? = null, taskId: String? = null, activate: Boolean = false, catalogs: List<UiCatalog> = uiCatalogs.forRun(), scope: ToolScope = ToolScope.All): PreparedRun {
        val e = effective(agent, sessionId, settings)
        val chat = if (modelFactoryOverride) CapabilityState() else e.provider?.availability(e.agent.config.model)
            ?: CapabilityState.unsupported("No model provider '${e.agent.config.provider}'; this model provider is not installed")
        if (activate) chat.requireReady()
        val run = RunContext(e.agent, sessionId, routine?.routine, taskId)
        val contributed = plugins.map { it.prepare(run, activate).ownedBy(it.instance, it.manifest) }
        val own = PluginTools(toolsFor(e, sessionId, routine, catalogs)).ownedBy(PluginInstanceId("kinuvo.core")).tools
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
            if (e.profile.tools.catalogExternal) it.size > 8 else usableTools.size > ToolCatalog.LIMIT
        }.orEmpty()
        // Only when the model can take the catalog's own tools; otherwise every tool stays direct.
        val finders = if (candidates.isEmpty()) emptyList() else PluginTools(ToolCatalog.tools(candidates)).ownedBy(PluginInstanceId("kinuvo.core")).tools
            .withModelNames(all.map { it.descriptor.name }.toSet()).takeIf { f -> usable(e, f, scope).size == f.size }.orEmpty()
        val hidden = if (finders.isEmpty()) emptyList() else candidates
        val reported = catalog + finders.map { ResolvedTool(it.descriptor.name, it.displayName, identity = it.identity) }
        return PreparedRun(e, usableTools + finders, usableTools - hidden.toSet() + finders, reported, contributed.flatMap { it.warnings }, chat)
    }

    suspend fun capabilities(agent: Agent, sessionId: String?, settings: SessionSettings?, server: ServerCapabilities, revision: Long = 0): SessionCapabilities {
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
    suspend fun runner(agent: Agent, sessionId: String? = null, routine: RoutineRun? = null, taskId: String? = null, catalogs: List<UiCatalog> = uiCatalogs.forRun(),
        scope: ToolScope = ToolScope.All): AgentRun {
        val prepared = prepare(agent, sessionId, routine = routine, taskId = taskId, activate = true, catalogs = catalogs, scope = scope)
        val e = prepared.effective
        val agent = e.agent
        val tools = prepared.tools.map(secrets::redacting)
        val advertised = prepared.advertised.map { it.descriptor.name }.toSet().let { names -> tools.filter { it.descriptor.name in names } }
        val sections = plugins.mapNotNull { it.instructions(agent) }
        val today = "${Clock.System.todayIn(timeZone)} ($timeZone)"
        val context = if (e.profile.prompt != PromptStyle.Full) basicContextFor(agent, docs, advertised, today, identity = e.profile.prompt == PromptStyle.BasicWithIdentity)
        else personalContextFor(agent, docs) + "\n" + capabilitiesFor(agent, advertised) + "\nServer platform: $platformDescription. Today is ${Clock.System.todayIn(timeZone)} ($timeZone).\n" + sections.joinToString("\n\n") { "## ${it.title}\n\n${it.body}" } +
            if (advertised.any { it.descriptor.name == ToolNames.ACTIVATE_SKILL }) "\n\nCurrent Skills:\n" + skillCatalogFor(skills.load(agent)) else ""
        val model = model(e)
        return AgentRun(
            agent = agent,
            store = store,
            model = model,
            contextTokens = e.info?.context,
            tracing = dependencies.tracing,
            tools = tools,
            advertisedTools = advertised.mapNotNull { it.descriptor.simplified(e.profile.tools.schema) },
            context = secrets.redact(context),
            warnings = prepared.warnings.map { secrets.redact(it) },
            approvals = sessionId?.let(sessionTrust::scope).let { session ->
                when (routine?.routine?.approvals) {
                    null -> Approvals(ApprovalPolicy(agent, trustRules, session = session, scope = scope), approvals)
                    RoutineApprovals.Auto -> Approvals(ApprovalPolicy(agent, trustRules, Tolerance.Full, session, scope), approvals)
                    RoutineApprovals.Deny -> Approvals(ApprovalPolicy(agent, trustRules, routineTolerance(agent), session, scope), approvals, ApprovalDecision.Deny(UNATTENDED_DENIAL))
                    RoutineApprovals.Inherit -> Approvals(ApprovalPolicy(agent, trustRules, routineTolerance(agent), session, scope), approvals, wait = routineApprovalWait, skipped = { routine.skipped += it })
                }
            },
            routine = routine,
            profile = e.profile,
            questions = questions,
            attach = { name, bytes -> attachments.upload(UploadAttachmentParams(name, Base64.encode(bytes))) },
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

    /** Runs a tool of the app's own MCP server for the app, under the Session's Approvals, asked in its Session. */
    suspend fun appCallTool(agent: Agent, request: McpAppToolRequest): JsonObject {
        val (apps, ref) = app(request.app)
        val sessionId = request.app.sessionId
        val asked = secrets.redacting(apps.tool(agent.id, ref, request.name))
        val policy = ApprovalPolicy(agent.with(store.session(agent.id, sessionId).settings), trustRules, session = sessionTrust.scope(sessionId))
        val args = request.arguments.toString()
        when (val verdict = policy.decide(asked, args)) {
            is Verdict.Block -> error(verdict.reason)
            // One Approval card at a time: a Session shows a single pending Approval.
            is Verdict.Ask -> appApprovalLock(sessionId).withLock {
                check(log.status(sessionId) == SessionStatus.Idle) { "The Agent is busy; try '${request.name}' again when it finishes" }
                val callId = "app-${request.app.callId}-${Uuid.random()}"
                val pending = approvals.register()
                var decision: ApprovalDecision? = null
                try {
                    log.publish(agent.id, sessionId, AgentEvent.ApprovalRequested(pending.id, callId, request.name, args, null,
                        verdict.assessment.risk, verdict.assessment.reason, verdict.trustKeys.isNotEmpty(), asked.identity))
                    decision = withTimeoutOrNull(APP_APPROVAL_WAIT) { pending.await() }
                } finally {
                    pending.close()
                    val allowed = decision != null && decision !is ApprovalDecision.Deny
                    withContext(NonCancellable) { log.publish(agent.id, sessionId, AgentEvent.ApprovalResolved(callId, allowed)) }
                }
                when (decision) {
                    null -> error("No answer to the Approval for '${request.name}'")
                    is ApprovalDecision.Deny -> error("The user denied '${request.name}'")
                    ApprovalDecision.AlwaysAllow -> policy.trust(asked, verdict.trustKeys)
                    ApprovalDecision.AllowForSession -> policy.trustForSession(asked, verdict.trustKeys)
                    else -> Unit
                }
            }
            Verdict.Run -> Unit
        }
        // Checked again: the server or tool may have been removed while the Approval waited.
        val tool = secrets.redacting(apps.tool(agent.id, ref, request.name))
        val result = tool.executeResult(request.arguments, "app-${request.app.callId}", object : ToolRun {
            override val tools = emptyList<ToolDescriptor>()
            override suspend fun call(name: String, args: String) = SessionEntry.ToolResult(name, name, "Unavailable to MCP Apps", isError = true)
        })
        return result.appResult ?: buildJsonObject {
            put("content", buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", result.output) }) })
            put("isError", result.isError)
        }
    }

    private val appApprovals = Mutex()
    private val appApprovalLocks = mutableMapOf<String, Mutex>()
    private suspend fun appApprovalLock(sessionId: String) = appApprovals.withLock { appApprovalLocks.getOrPut(sessionId) { Mutex() } }

    /** Stops everything the plugins run, e.g. before the process exits. */
    suspend fun close() { computers.close(); dependencies.resources.close() }

    /** Stops what the plugins run for the Agent, e.g. after its exec settings changed. */
    suspend fun release(agent: Agent) = dependencies.resources.release(agent.id)

    private suspend fun toolsFor(e: Effective, sessionId: String?, routine: RoutineRun?, catalogs: List<UiCatalog>): List<Tool> {
        val agent = e.agent
        val search = if (ToolNames.WEB_FETCH in agent.config.tools) webSearch() else null
        val selected = imageModel
        val provider = selected?.let { providers[it.provider] }
        val imageTool = if (selected != null && sessionId != null && provider?.images != null &&
            provider.availability(selected.model).actionable) GenerateImageTool(provider.images!!, selected.model, {
                store.entries(agent.id, sessionId).flatMap { entry -> when (entry) {
                    is SessionEntry.UserMessage -> entry.attachments
                    is SessionEntry.ToolResult -> entry.attachments
                    else -> emptyList()
                } }
            }, attachments::bytes) else null
        val uiTool = catalogs.takeIf { sessionId != null && it.isNotEmpty() }?.let(::ShowUiTool)
        return builtInTools(e, sessionId, routine, search) + listOfNotNull(search?.let(::WebSearchTool), imageTool, uiTool, uiTool?.let { ShowHtmlTool() })
    }

    private suspend fun builtInTools(e: Effective, sessionId: String?, routine: RoutineRun?, search: WebSearch?): List<Tool> = e.agent.let { agent -> agent.config.tools.mapNotNull {
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
