package kinuvo.server.tools

import kinuvo.harness.ToolSpec
import kinuvo.server.*
import kinuvo.server.plugin.*
import kinuvo.protocol.ProtocolJson
import kinuvo.protocol.agent.*
import kinuvo.protocol.approval.*
import kinuvo.protocol.capability.Integration
import kinuvo.protocol.mcp.*
import kinuvo.protocol.session.*
import kinuvo.protocol.tool.ToolNames
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.*

/** What the management tools read and change; the Server supplies it from the same handlers as Settings. */
class AgentManagement(private val harness: HarnessRuntime, private val admin: Admin) {
    val toolGroups get() = harness.integrations[Integration.Tools].orEmpty().toSet()
    suspend fun agents() = admin.agents()
    suspend fun details(id: AgentId) = throwing<AgentError, AgentDetails> { admin.details(admin.agent(id)) }
    suspend fun update(p: UpdateAgentParams) = throwing<AgentError, AgentDetails> { admin.update(p) }
    suspend fun create(p: CreateAgentParams) = throwing<AgentError, AgentInfo> { admin.create(p) }
    suspend fun session(agent: AgentId, session: SessionId) = harness.store.session(agent, session)
    suspend fun check(agent: AgentId, session: SessionId, settings: SessionSettings) {
        harness.checkConfigure(harness.store.agents().first { it.id == agent }, session, settings)
    }
    suspend fun configure(agent: AgentId, session: SessionId, settings: SessionSettings) =
        harness.configure(harness.store.agents().first { it.id == agent }, session, settings)
    suspend fun mcpServers(id: AgentId) = harness.mcp.mcpServers(harness.store.agents().first { it.id == id })
    suspend fun saveMcp(p: SaveMcpParams) = harness.mcp.saveMcp(p)
    suspend fun removeMcp(p: McpParams) = harness.mcp.removeMcp(p)
    suspend fun testMcp(p: McpParams) = harness.mcp.testMcp(p)
    suspend fun loginMcp(p: McpLoginParams) = harness.mcp.loginMcp(p).url
    suspend fun completeMcpLogin(p: McpLoginCompleteParams) = harness.mcp.completeMcpLogin(p)
}

data class AgentManageScope(val agentId: AgentId, val sessionId: SessionId?)

/**
 * The tools an Agent changes its own setup with: its settings, new Agents, its MCP servers and the current Session's
 * model. Approvals show each change before → after; none can loosen the Agent's safety: Risk tolerance,
 * Trust and Deny rules, and tool groups the Server lacks are the user's to change in Settings.
 */
class AgentManageTools(private val management: AgentManagement) {
    /** A Hidden Session only gets [SessionConfigure], which keeps it on-device: what it changes elsewhere would outlive it in documents other Sessions read. */
    fun tools(scope: AgentManageScope, hidden: Boolean = false): List<Tool> =
        if (hidden) listOf(SessionConfigure(management, scope)) else listOf(
            AgentGet(management, scope), AgentUpdate(management, scope), AgentCreate(management, scope), McpList(management, scope),
            McpAdd(management, scope), McpRemove(management, scope), McpTest(management, scope), McpLogin(management, scope), SessionConfigure(management, scope),
        )
}

private const val SETTINGS_ONLY = "Risk tolerance, Trust and Deny rules and where the shell runs can only be changed by the user, in Settings → Agent. Tell them what you'd change and why."

private abstract class ManageTool(protected val management: AgentManagement, protected val scope: AgentManageScope) : CoreTool {
    /** A change always asks, whatever the Risk tolerance, and "Always allow" isn't offered for it. */
    protected fun asks(reason: String) = Assessment(listOf(Action(risk, listOf(descriptor.name), reason, trustable = false, alwaysAsk = true)))

    override suspend fun bind(args: JsonObject): suspend () -> Unit {
        val allowed = descriptor.schema["properties"]?.jsonObject?.keys.orEmpty()
        require(args.keys.all { it in allowed }) { "${(args.keys - allowed).joinToString()} can't be changed from a chat. $SETTINGS_ONLY" }
        return {}
    }

    // Some providers send every optional parameter, blank when unused.
    protected fun JsonObject.clean() = JsonObject(filterValues { (it as? JsonPrimitive)?.content?.isNotBlank() != false })
    protected fun JsonObject.list(key: String) = string(key)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
    protected fun JsonObject.strings(key: String) = (get(key) as? JsonArray)?.map { (it as JsonPrimitive).content }.orEmpty()
    protected fun JsonObject.map(key: String) = (get(key) as? JsonObject)?.mapValues { (it.value as JsonPrimitive).content }.orEmpty()
}

private class Changes {
    private val fields = mutableListOf<FieldChange>()
    val isEmpty get() = fields.isEmpty()
    fun field(label: String, old: String?, new: String?) {
        if (new != null && new != old) fields += FieldChange(label, old, new)
    }
    fun preview(title: String) = ChangePreview(title, fields)
    fun text(title: String) = preview(title).text()
}

private fun model(provider: String, model: String) = "$provider/$model"

private class AgentGet(management: AgentManagement, scope: AgentManageScope) : ManageTool(management, scope) {
    override val descriptor = toolSpec(ToolNames.AGENT_GET, "Your own settings: name, Identity, Soul, avatar, model, reasoning effort, Risk tolerance, tool groups and MCP servers.")

    override suspend fun execute(args: JsonObject): String {
        val d = management.details(scope.agentId)
        val s = d.settings
        return buildString {
            appendLine("${d.info.id} \"${d.info.name}\"")
            appendLine("model: ${model(s.provider, s.model)}, reasoning ${s.reasoning}")
            appendLine("Risk tolerance: ${s.approvals} (only the user changes it)")
            appendLine("avatar: ${d.info.avatar ?: "none"}")
            appendLine("tool groups: ${s.tools.joinToString()}")
            appendLine("MCP servers: ${management.mcpServers(scope.agentId).joinToString { it.name }.ifEmpty { "none" }}")
            appendLine("\nIdentity:\n${d.identity.trim().take(MAX_DOC)}")
            append("\nSoul:\n${d.soul.trim().take(MAX_DOC)}")
        }
    }

    private companion object { const val MAX_DOC = 8_000 }
}

private class AgentUpdate(management: AgentManagement, scope: AgentManageScope) : ManageTool(management, scope) {
    override val descriptor = toolSpec(
        name = ToolNames.AGENT_UPDATE,
        description = "Change your own name, Identity (who you are, as IDENTITY.md), Soul (principles and tone, SOUL.md), avatar, default model, reasoning effort or tool groups. " +
            "The user sees exactly what changes and approves it. Identity and Soul replace the whole document, so read it first with agent_get. " +
            "You can't change Risk tolerance, Trust or Deny rules; the user does that in Settings.",
        optionalParameters = listOf(
            ToolParameter("name", "Your display name", ParameterType.String),
            ToolParameter("identity", "The new IDENTITY.md text", ParameterType.String),
            ToolParameter("soul", "The new SOUL.md text", ParameterType.String),
            ToolParameter("avatar", "An avatar artboard ID", ParameterType.String),
            ToolParameter("provider", "Model provider; needs model", ParameterType.String),
            ToolParameter("model", "Default model, of the provider if given, else your current one", ParameterType.String),
            ToolParameter("reasoning", "Default reasoning effort", ParameterType.String),
            ToolParameter("enable_tools", "Tool groups to turn on, separated by commas", ParameterType.String),
            ToolParameter("disable_tools", "Tool groups to turn off, separated by commas", ParameterType.String),
        ),
    )

    override fun assess(args: JsonObject) = asks("Changes this Agent's own settings")

    override suspend fun preview(args: JsonObject) = plan(args).second.also { require(!it.isEmpty) { "Nothing to change." } }.preview("Changes to ${scope.agentId}")

    override suspend fun execute(args: JsonObject): String {
        val (params, changes) = plan(args)
        if (changes.isEmpty) return "Nothing to change."
        management.update(params)
        return changes.text("Updated ${scope.agentId}")
    }

    private suspend fun plan(args: JsonObject): Pair<UpdateAgentParams, Changes> {
        val a = args.clean()
        val d = management.details(scope.agentId)
        val enable = a.list("enable_tools")
        val disable = a.list("disable_tools")
        (enable + disable).forEach { require(it in ToolNames.OPTIONAL) { "Unknown tool group '$it'; change this in Settings. Available groups are ${ToolNames.OPTIONAL.joinToString()}" } }
        enable.filter { it !in d.settings.tools }.forEach {
            require(it in management.toolGroups) { "Tool group '$it' is off on this Server, so it can't be turned on from a chat. Ask the user to enable it in Settings → Tools & MCP." }
        }
        val tools = (d.settings.tools + enable).distinct() - disable.toSet()
        val provider = a.string("provider")
        require(provider == null || a.string("model") != null) { "Give a model with its provider" }
        val name = a.string("name")
        require(name == null || name.length <= MAX_NAME) { "Names are at most $MAX_NAME characters" }
        val params = UpdateAgentParams(
            agentId = scope.agentId,
            // Identity's title is the name, so changing one keeps the other.
            name = (name ?: d.info.name).takeIf { name != null || a.string("identity") != null },
            identity = a.string("identity"), soul = a.string("soul"), avatar = a.string("avatar"),
            provider = provider, model = a.string("model"), reasoning = a.string("reasoning"),
            tools = tools.takeIf { it != d.settings.tools },
        )
        val changes = Changes().apply {
            field("Name", d.info.name, name)
            field("Identity", d.identity, params.identity)
            field("Soul", d.soul, params.soul)
            field("Avatar", d.info.avatar, params.avatar)
            field("Model", model(d.settings.provider, d.settings.model), params.model?.let { model(provider ?: d.settings.provider, it) })
            field("Reasoning", d.settings.reasoning, params.reasoning)
            params.tools?.let { new ->
                field("Tool groups", d.settings.tools.joinToString(), new.joinToString())
            }
        }
        return params to changes
    }

    private companion object {
        const val MAX_NAME = 60
    }
}

private class AgentCreate(management: AgentManagement, scope: AgentManageScope) : ManageTool(management, scope) {
    override val descriptor = toolSpec(
        name = ToolNames.AGENT_CREATE,
        description = "Create a new, blank Agent. It starts from the defaults, not from you: it doesn't get your Memory, Skills, Trust rules or the MCP servers you added. " +
            "The user sees the new Agent's settings and approves it.",
        requiredParameters = listOf(ToolParameter("name", "The new Agent's display name", ParameterType.String)),
        optionalParameters = listOf(
            ToolParameter("identity", "Its IDENTITY.md text", ParameterType.String),
            ToolParameter("soul", "Its SOUL.md text", ParameterType.String),
            ToolParameter("provider", "Model provider; needs model", ParameterType.String),
            ToolParameter("model", "Its default model", ParameterType.String),
        ),
    )

    override fun assess(args: JsonObject) = asks("Creates a new Agent")

    override suspend fun preview(args: JsonObject): ChangePreview {
        val a = args.clean()
        val name = a.requireString("name")
        require(a.string("provider") == null || a.string("model") != null) { "Give a model with its provider" }
        return Changes().apply {
            field("Agent", null, "$name (${freeId(name)}), blank: default settings, no Memory, Skills, MCP servers or rules from you")
            field("Identity", null, a.string("identity"))
            field("Soul", null, a.string("soul"))
            field("Model", null, a.string("model")?.let { model(a.string("provider") ?: "default provider", it) })
        }.preview("New Agent")
    }

    override suspend fun execute(args: JsonObject): String {
        val a = args.clean()
        val name = a.requireString("name")
        require(a.string("provider") == null || a.string("model") != null) { "Give a model with its provider" }
        val info = management.create(CreateAgentParams(freeId(name), name))
        if (listOf("identity", "soul", "provider", "model").any { it in a }) {
            management.update(UpdateAgentParams(info.id, name = name, identity = a.string("identity"), soul = a.string("soul"), provider = a.string("provider"), model = a.string("model")))
        }
        return "Created Agent ${info.id} \"$name\". It starts from the defaults; hand it work with agents_spawn."
    }

    private suspend fun freeId(name: String): AgentId {
        val taken = management.agents().map { it.id.value }.toSet()
        val base = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(MAX_ID - 3).trim('-').ifEmpty { "agent" }
        return generateSequence(1) { it + 1 }.map { if (it == 1) base else "$base-$it" }.first { it !in taken }.let(::AgentId)
    }

    private companion object { const val MAX_ID = 32 }
}

private class McpList(management: AgentManagement, scope: AgentManageScope) : ManageTool(management, scope) {
    override val descriptor = toolSpec(ToolNames.MCP_LIST, "Your MCP servers: name, how it connects, whether you're signed in and which are shared with other Agents.")

    override suspend fun execute(args: JsonObject): String = management.mcpServers(scope.agentId).joinToString("\n") { s ->
        val auth = when (s.auth) { McpAuthStatus.Required -> ", sign-in needed"; McpAuthStatus.SignedIn -> ", signed in"; McpAuthStatus.None -> "" }
        "- ${s.name} [${s.url ?: "${s.command} ${s.args.joinToString(" ")}".trim()}] ${s.scope.name.lowercase()}$auth${if (!s.active) ", replaced by your own" else ""}"
    }.ifEmpty { "No MCP servers." }
}

private class McpAdd(management: AgentManagement, scope: AgentManageScope) : ManageTool(management, scope) {
    override val descriptor = ToolSpec(ToolNames.MCP_ADD, "Add or replace your own MCP server. Give url or command, not both. Secret env and headers values are hidden from Approval previews.", buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            listOf("name", "url", "command").forEach { key -> putJsonObject(key) { put("type", "string") } }
            putJsonObject("args") { put("type", "array"); putJsonObject("items") { put("type", "string") } }
            listOf("env", "headers").forEach { key -> putJsonObject(key) { put("type", "object"); putJsonObject("additionalProperties") { put("type", "string") } } }
        }
        put("required", JsonArray(listOf(JsonPrimitive("name"))))
    })

    override fun assess(args: JsonObject) = asks("Adds an MCP server, which can run code on the Server or send data to a service")

    override suspend fun preview(args: JsonObject): ChangePreview {
        val s = server(args)
        val old = management.mcpServers(scope.agentId).firstOrNull { it.name == s.name && it.scope == ConfigScope.Agent }
        return Changes().apply {
            field("MCP server", old?.let { "${it.name} [${connection(it)}]" }, "${s.name} [${connection(s)}]")
            field("Environment", old?.env?.keys?.joinToString(), s.env.keys.joinToString().takeIf { s.env.isNotEmpty() })
            field("Headers", old?.headers?.keys?.joinToString(), s.headers.keys.joinToString().takeIf { s.headers.isNotEmpty() })
        }.preview(if (old == null) "Adds an MCP server to ${scope.agentId}" else "Replaces an MCP server of ${scope.agentId}").copy(note = "Environment and header values are not shown.")
    }

    override suspend fun execute(args: JsonObject): String {
        val saved = management.saveMcp(SaveMcpParams(scope.agentId, server(args)))
        return "Added '${saved.name}'. Try it with mcp_test."
    }

    private fun server(args: JsonObject): McpServerInfo {
        val a = args.clean()
        val url = a.string("url")
        val command = a.string("command")
        require((url == null) != (command == null)) { "Give either a url or a command" }
        return McpServerInfo(a.requireString("name"), ConfigScope.Agent, command, a.strings("args"), a.map("env"), url, a.map("headers"))
    }

    private fun connection(s: McpServerInfo) = s.url ?: "${s.command} ${s.args.joinToString(" ")}".trim()
}

private class McpRemove(management: AgentManagement, scope: AgentManageScope) : ManageTool(management, scope) {
    override val descriptor = toolSpec(
        name = ToolNames.MCP_REMOVE,
        description = "Remove one of your own MCP servers. Only when the user named this server in their request; never to tidy up or fix a problem on your own. Shared servers are the user's to remove in Settings.",
        requiredParameters = listOf(ToolParameter("name", "The server's name from mcp_list", ParameterType.String)),
    )

    override fun assess(args: JsonObject) = asks("Removes an MCP server and its sign-in")

    override suspend fun preview(args: JsonObject): ChangePreview {
        val s = own(args.requireString("name"))
        return Changes().apply { field("MCP server", "${s.name} [${s.url ?: s.command}]", "(removed)") }.preview("Removes an MCP server from ${scope.agentId}")
    }

    override suspend fun execute(args: JsonObject): String {
        val s = own(args.requireString("name"))
        management.removeMcp(McpParams(scope.agentId, s.name, ConfigScope.Agent))
        return "Removed '${s.name}'."
    }

    private suspend fun own(name: String) = management.mcpServers(scope.agentId).firstOrNull { it.name == name && it.scope == ConfigScope.Agent }
        ?: throw IllegalArgumentException("No MCP server '$name' of your own; shared ones are removed by the user in Settings")
}

private class McpTest(management: AgentManagement, scope: AgentManageScope) : ManageTool(management, scope) {
    override val descriptor = toolSpec(
        name = ToolNames.MCP_TEST,
        description = "Connect to one of your MCP servers and list the tools it offers, or say why it can't connect or whether it needs mcp_login.",
        requiredParameters = listOf(ToolParameter("name", "The server's name from mcp_list", ParameterType.String)),
    )

    override suspend fun execute(args: JsonObject): String {
        val name = args.requireString("name")
        val result = management.testMcp(McpParams(scope.agentId, name))
        return when {
            result.needsLogin -> "'$name' needs sign-in: use mcp_login. ${result.error.orEmpty()}".trim()
            result.error != null -> "'$name' failed: ${result.error}"
            else -> "'$name' works: ${result.tools.size} tools: ${result.tools.joinToString()}"
        }
    }
}

private class McpLogin(management: AgentManagement, scope: AgentManageScope) : ManageTool(management, scope) {
    override val descriptor = toolSpec(
        name = ToolNames.MCP_LOGIN,
        description = "Sign in to one of your HTTP MCP servers through OAuth. The user gets a sign-in link in a form, opens it, and pastes back the address their browser ends on; then the sign-in completes.",
        requiredParameters = listOf(ToolParameter("name", "The server's name from mcp_list", ParameterType.String)),
    )

    override suspend fun preview(args: JsonObject) = ChangePreview("Sign in to ${args.requireString("name")}", body = "The user gets a link to open.")

    override suspend fun execute(args: JsonObject): String = error("MCP sign-in requires a run")
    override suspend fun execute(args: JsonObject, callId: String, run: ToolRun): String {
        val name = args.requireString("name")
        val url = management.loginMcp(McpLoginParams(scope.agentId, name))
        val question = Question("address", "Open this link and sign in: $url\n\nPaste the address your browser ends on.", QuestionKind.LongText, required = true)
        val form = buildJsonObject {
            put("title", "Sign in to $name")
            put("questions", ProtocolJson.encodeToJsonElement(ListSerializer(Question.serializer()), listOf(question)))
        }
        val result = run.call(ToolNames.ASK_USER, form.toString())
        check(!result.isError) { "Couldn't show the sign-in link: ${result.output}" }
        val answer = runCatching { ProtocolJson.parseToJsonElement(result.output).jsonObject.string("address") }.getOrNull()
            ?: return "The user skipped the sign-in."
        return "Signed in to '${management.completeMcpLogin(McpLoginCompleteParams(answer.trim())).name}'."
    }
}

private class SessionConfigure(management: AgentManagement, scope: AgentManageScope) : ManageTool(management, scope) {
    override val descriptor = toolSpec(
        name = ToolNames.SESSION_CONFIGURE,
        description = "Change the model or reasoning effort of this Session only; your own defaults stay as they are. A Hidden Session keeps an on-device model. " +
            "You can make the Session stricter about Risk tolerance but never looser; the user raises it in Settings.",
        optionalParameters = listOf(
            ToolParameter("provider", "Model provider; needs model", ParameterType.String),
            ToolParameter("model", "A model of the provider if given, else of your current one", ParameterType.String),
            ToolParameter("reasoning", "Reasoning effort", ParameterType.String),
            ToolParameter("approvals", "A stricter Risk tolerance: strict, default or relaxed, never above the current one", ParameterType.String),
        ),
    )

    override suspend fun preview(args: JsonObject): ChangePreview {
        val (session, settings, changes) = plan(args)
        require(!changes.isEmpty) { "Nothing to change." }
        management.check(session.agentId, session.id, settings)
        return changes.preview("Changes to this Session")
    }

    override suspend fun execute(args: JsonObject): String {
        val (session, settings, changes) = plan(args)
        if (changes.isEmpty) return "Nothing to change."
        management.configure(session.agentId, session.id, settings)
        return changes.text("Updated this Session")
    }

    private suspend fun plan(args: JsonObject): Triple<SessionInfo, SessionSettings, Changes> {
        val a = args.clean()
        val session = management.session(scope.agentId, requireNotNull(scope.sessionId) { "This needs a Session" })
        val d = management.details(scope.agentId)
        val current = session.settings
        require(a.string("provider") == null || a.string("model") != null) { "Give a model with its provider" }
        val approvals = a.string("approvals")?.also {
            require(it in Tolerance.ids) { "approvals must be one of ${Tolerance.ids.joinToString()}" }
            val now = Tolerance.of(current.approvals ?: d.settings.approvals)
            require(Tolerance.of(it) <= now) { "Risk tolerance can't be raised from a chat (it is ${now.id}). The user changes it in this Session's settings." }
        }
        val settings = SessionSettings(
            model = a.string("model") ?: current.model, reasoning = a.string("reasoning") ?: current.reasoning,
            approvals = approvals ?: current.approvals, provider = a.string("provider") ?: current.provider,
        )
        val changes = Changes().apply {
            field("Model", model(current.provider ?: d.settings.provider, current.model ?: d.settings.model), settings.takeIf { a.string("model") != null }?.let { model(it.provider ?: d.settings.provider, it.model!!) })
            field("Reasoning", current.reasoning ?: d.settings.reasoning, a.string("reasoning"))
            field("Risk tolerance", current.approvals ?: d.settings.approvals, approvals)
        }
        return Triple(session, settings, changes)
    }
}
