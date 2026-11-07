package kinuvo.harness.core.agenttools

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import kinuvo.harness.core.Tolerance
import kinuvo.harness.core.tools.Action
import kinuvo.harness.core.tools.Assessment
import kinuvo.harness.core.tools.RawSchemaToolDescriptor
import kinuvo.harness.core.tools.Tool
import kinuvo.harness.core.tools.ToolNames
import kinuvo.harness.core.tools.requireString
import kinuvo.harness.core.tools.string
import kinuvo.protocol.AgentDetails
import kinuvo.protocol.AgentInfo
import kinuvo.protocol.ConfigScope
import kinuvo.protocol.CreateAgentParams
import kinuvo.protocol.McpAuthStatus
import kinuvo.protocol.McpLoginCompleteParams
import kinuvo.protocol.McpLoginParams
import kinuvo.protocol.McpParams
import kinuvo.protocol.McpServerInfo
import kinuvo.protocol.McpTestResult
import kinuvo.protocol.Question
import kinuvo.protocol.QuestionKind
import kinuvo.protocol.Risk
import kinuvo.protocol.SaveMcpParams
import kinuvo.protocol.SessionInfo
import kinuvo.protocol.SessionSettings
import kinuvo.protocol.UpdateAgentParams
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** What the management tools read and change; the Server supplies it from the same handlers as Settings. */
interface AgentManagement {
    /** The built-in tool groups this Server can run; an Agent can't turn on any other. */
    val toolGroups: Set<String>
    suspend fun agents(): List<AgentInfo>
    suspend fun details(agentId: String): AgentDetails
    suspend fun update(p: UpdateAgentParams): AgentDetails
    suspend fun create(p: CreateAgentParams): AgentInfo
    suspend fun session(agentId: String, sessionId: String): SessionInfo
    /** Fails when [settings] aren't allowed for the Session, e.g. a model off the device for a Hidden Session. */
    suspend fun check(agentId: String, sessionId: String, settings: SessionSettings)
    suspend fun configure(agentId: String, sessionId: String, settings: SessionSettings): SessionInfo
    suspend fun mcpServers(agentId: String): List<McpServerInfo>
    suspend fun saveMcp(p: SaveMcpParams): McpServerInfo
    suspend fun removeMcp(p: McpParams)
    suspend fun testMcp(p: McpParams): McpTestResult
    suspend fun loginMcp(p: McpLoginParams): String
    suspend fun completeMcpLogin(p: McpLoginCompleteParams): McpServerInfo
    /** Shows [questions] to the user in the caller's Session and waits for the answers; null when they skip. */
    suspend fun ask(scope: CallerScope, callId: String?, title: String, questions: List<Question>): Map<String, List<String>>?
}

/**
 * The tools an Agent changes its own setup with: its settings, new Agents, its MCP servers and the current Session's
 * model. Every change asks first with a before → after summary, and none can loosen the Agent's safety: Risk tolerance,
 * Trust and Deny rules, and tool groups the Server lacks are the user's to change in Settings.
 */
class AgentManageTools(private val management: AgentManagement) {
    /** A Hidden Session only gets [SessionConfigure], which keeps it on-device: what it changes elsewhere would outlive it in documents other Sessions read. */
    fun tools(scope: CallerScope, hidden: Boolean = false): List<Tool> =
        if (hidden) listOf(SessionConfigure(management, scope)) else listOf(
            AgentGet(management, scope), AgentUpdate(management, scope), AgentCreate(management, scope), McpList(management, scope),
            McpAdd(management, scope), McpRemove(management, scope), McpTest(management, scope), McpLogin(management, scope), SessionConfigure(management, scope),
        )
}

private const val SETTINGS_ONLY = "Risk tolerance, Trust and Deny rules and where the shell runs can only be changed by the user, in Settings → Agent. Tell them what you'd change and why."

private abstract class ManageTool(protected val management: AgentManagement, protected val scope: CallerScope) : Tool {
    override val risk = Risk.High
    override val repeatable = false

    /** A change always asks, whatever the Risk tolerance, and "Always allow" isn't offered for it. */
    protected fun asks(reason: String, risk: Risk = Risk.High) = Assessment(listOf(Action(risk, listOf(descriptor.name), reason, trustable = false, alwaysAsk = true)))

    // Some providers send every optional parameter, blank when unused.
    protected fun JsonObject.clean() = JsonObject(filterValues { (it as? JsonPrimitive)?.content?.isNotBlank() != false })
    protected fun JsonObject.list(key: String) = string(key)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
    protected fun JsonObject.strings(key: String) = (get(key) as? JsonArray)?.map { (it as JsonPrimitive).content }.orEmpty()
    protected fun JsonObject.map(key: String) = (get(key) as? JsonObject)?.mapValues { (it.value as JsonPrimitive).content }.orEmpty()
}

private class Changes {
    private val lines = mutableListOf<String>()
    val isEmpty get() = lines.isEmpty()

    fun field(label: String, old: String?, new: String?) {
        if (new != null && new != old) lines += "$label: ${show(old)} → ${show(new)}"
    }

    fun text(heading: String) = if (isEmpty) "$heading: no changes." else "$heading:\n" + lines.joinToString("\n") { "- $it" }

    private fun show(value: String?): String {
        val text = value?.trim().orEmpty()
        return when {
            text.isEmpty() -> "(none)"
            '\n' !in text && text.length <= SHORT -> text
            else -> "\"" + text.take(EXCERPT).replace('\n', ' ') + (if (text.length > EXCERPT) "…" else "") + "\" (${text.length} characters)"
        }
    }

    private companion object {
        const val SHORT = 120
        const val EXCERPT = 400
    }
}

private fun model(provider: String, model: String) = "$provider/$model"

private class AgentGet(management: AgentManagement, scope: CallerScope) : ManageTool(management, scope) {
    override val risk = Risk.Low
    override val repeatable = true
    override val descriptor = ToolDescriptor("agent_get", "Your own settings: name, Identity, Soul, avatar, model, reasoning effort, Risk tolerance, tool groups and MCP servers.")

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

private class AgentUpdate(management: AgentManagement, scope: CallerScope) : ManageTool(management, scope) {
    override val descriptor = ToolDescriptor(
        name = "agent_update",
        description = "Change your own name, Identity (who you are, as IDENTITY.md), Soul (principles and tone, SOUL.md), avatar, default model, reasoning effort or tool groups. " +
            "The user sees exactly what changes and approves it. Identity and Soul replace the whole document, so read it first with agent_get. " +
            "You can't change Risk tolerance, Trust or Deny rules; the user does that in Settings.",
        optionalParameters = listOf(
            ToolParameterDescriptor("name", "Your display name", ToolParameterType.String),
            ToolParameterDescriptor("identity", "The new IDENTITY.md text", ToolParameterType.String),
            ToolParameterDescriptor("soul", "The new SOUL.md text", ToolParameterType.String),
            ToolParameterDescriptor("avatar", "An avatar artboard ID", ToolParameterType.String),
            ToolParameterDescriptor("provider", "Model provider; needs model", ToolParameterType.String),
            ToolParameterDescriptor("model", "Default model, of the provider if given, else your current one", ToolParameterType.String),
            ToolParameterDescriptor("reasoning", "Default reasoning effort", ToolParameterType.String),
            ToolParameterDescriptor("enable_tools", "Tool groups to turn on, separated by commas", ToolParameterType.String),
            ToolParameterDescriptor("disable_tools", "Tool groups to turn off, separated by commas", ToolParameterType.String),
        ),
    )

    override fun assess(args: JsonObject) = asks("Changes this Agent's own settings")

    override suspend fun describe(args: JsonObject) = plan(args).second.text("Changes to ${scope.agentId}")

    override suspend fun execute(args: JsonObject): String {
        val (params, changes) = plan(args)
        if (changes.isEmpty) return "Nothing to change."
        management.update(params)
        return changes.text("Updated ${scope.agentId}")
    }

    private suspend fun plan(args: JsonObject): Pair<UpdateAgentParams, Changes> {
        val a = args.clean()
        require(FORBIDDEN.none { it in a }) { SETTINGS_ONLY }
        val d = management.details(scope.agentId)
        val enable = a.list("enable_tools")
        val disable = a.list("disable_tools")
        (enable + disable).forEach { require(it in ToolNames.OPTIONAL) { "Unknown tool group '$it'; groups are ${ToolNames.OPTIONAL.joinToString()}" } }
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
        val FORBIDDEN = setOf("approvals", "risk_tolerance", "tolerance", "rules", "trust", "trusted", "deny", "exec", "workspace")
    }
}

private class AgentCreate(management: AgentManagement, scope: CallerScope) : ManageTool(management, scope) {
    override val descriptor = ToolDescriptor(
        name = "agent_create",
        description = "Create a new, blank Agent. It starts from the defaults, not from you: it doesn't get your Memory, Skills, Trust rules or the MCP servers you added. " +
            "The user sees the new Agent's settings and approves it.",
        requiredParameters = listOf(ToolParameterDescriptor("name", "The new Agent's display name", ToolParameterType.String)),
        optionalParameters = listOf(
            ToolParameterDescriptor("identity", "Its IDENTITY.md text", ToolParameterType.String),
            ToolParameterDescriptor("soul", "Its SOUL.md text", ToolParameterType.String),
            ToolParameterDescriptor("provider", "Model provider; needs model", ToolParameterType.String),
            ToolParameterDescriptor("model", "Its default model", ToolParameterType.String),
        ),
    )

    override fun assess(args: JsonObject) = asks("Creates a new Agent")

    override suspend fun describe(args: JsonObject): String {
        val a = args.clean()
        val name = a.requireString("name")
        return Changes().apply {
            field("Agent", null, "$name (${freeId(name)}), blank: default settings, no Memory, Skills, MCP servers or rules from you")
            field("Identity", null, a.string("identity"))
            field("Soul", null, a.string("soul"))
            field("Model", null, a.string("model")?.let { model(a.string("provider") ?: "default provider", it) })
        }.text("New Agent")
    }

    override suspend fun execute(args: JsonObject): String {
        val a = args.clean()
        val name = a.requireString("name")
        require(a.string("provider") == null || a.string("model") != null) { "Give a model with its provider" }
        val info = management.create(CreateAgentParams(freeId(name), name))
        if (listOf("identity", "soul", "provider", "model").any { it in a }) {
            management.update(UpdateAgentParams(info.id, name = name, identity = a.string("identity"), soul = a.string("soul"), provider = a.string("provider"), model = a.string("model")))
        }
        return "Created Agent ${info.id} \"$name\". It starts from the defaults; hand it work with delegate."
    }

    private suspend fun freeId(name: String): String {
        val taken = management.agents().map { it.id }.toSet()
        val base = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(MAX_ID - 3).trim('-').ifEmpty { "agent" }
        return generateSequence(1) { it + 1 }.map { if (it == 1) base else "$base-$it" }.first { it !in taken }
    }

    private companion object { const val MAX_ID = 32 }
}

private class McpList(management: AgentManagement, scope: CallerScope) : ManageTool(management, scope) {
    override val risk = Risk.Low
    override val repeatable = true
    override val descriptor = ToolDescriptor("mcp_list", "Your MCP servers: name, how it connects, whether you're signed in and which are shared with other Agents.")

    override suspend fun execute(args: JsonObject): String = management.mcpServers(scope.agentId).joinToString("\n") { s ->
        val auth = when (s.auth) { McpAuthStatus.Required -> ", sign-in needed"; McpAuthStatus.SignedIn -> ", signed in"; McpAuthStatus.None -> "" }
        "- ${s.name} [${s.url ?: "${s.command} ${s.args.joinToString(" ")}".trim()}] ${s.scope.name.lowercase()}$auth${if (!s.active) ", replaced by your own" else ""}"
    }.ifEmpty { "No MCP servers." }
}

private class McpAdd(management: AgentManagement, scope: CallerScope) : ManageTool(management, scope) {
    override val descriptor = RawSchemaToolDescriptor(
        "mcp_add",
        "Add an MCP server to yourself: an HTTP one with url, or a local one with command (it runs on the Server). Only yours; other Agents don't get it. " +
            "Put secrets in env or headers as references such as \${VAR} or pass://..., never as plain values. The user sees the server and approves it. " +
            "Then try it with mcp_test, and mcp_login if it needs sign-in.",
        buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("name") { put("type", "string"); put("description", "A short name: letters, digits, '.', '_' and '-'") }
                putJsonObject("url") { put("type", "string"); put("description", "An HTTP MCP server's URL") }
                putJsonObject("command") { put("type", "string"); put("description", "A local MCP server's command") }
                putJsonObject("args") { put("type", "array"); putJsonObject("items") { put("type", "string") }; put("description", "The command's arguments") }
                putJsonObject("env") { put("type", "object"); put("description", "The command's environment variables") }
                putJsonObject("headers") { put("type", "object"); put("description", "Headers for the HTTP server") }
            }
            put("required", JsonArray(listOf(JsonPrimitive("name"))))
        },
    )

    override fun assess(args: JsonObject) = asks("Adds an MCP server, which can run code on the Server or send data to a service")

    override suspend fun describe(args: JsonObject): String {
        val s = server(args)
        val old = management.mcpServers(scope.agentId).firstOrNull { it.name == s.name && it.scope == ConfigScope.Agent }
        return Changes().apply {
            field("MCP server", old?.let { "${it.name} [${connection(it)}]" }, "${s.name} [${connection(s)}]")
            field("Environment", old?.env?.keys?.joinToString(), s.env.keys.joinToString().takeIf { s.env.isNotEmpty() })
            field("Headers", old?.headers?.keys?.joinToString(), s.headers.keys.joinToString().takeIf { s.headers.isNotEmpty() })
        }.text(if (old == null) "Adds an MCP server to ${scope.agentId}" else "Replaces an MCP server of ${scope.agentId}") + "\nValues are not shown."
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

private class McpRemove(management: AgentManagement, scope: CallerScope) : ManageTool(management, scope) {
    override val descriptor = ToolDescriptor(
        name = "mcp_remove",
        description = "Remove one of your own MCP servers. Only when the user named this server in their request; never to tidy up or fix a problem on your own. Shared servers are the user's to remove in Settings.",
        requiredParameters = listOf(ToolParameterDescriptor("name", "The server's name from mcp_list", ToolParameterType.String)),
    )

    override fun assess(args: JsonObject) = asks("Removes an MCP server and its sign-in")

    override suspend fun describe(args: JsonObject): String {
        val s = own(args.requireString("name"))
        return Changes().apply { field("MCP server", "${s.name} [${s.url ?: s.command}]", "(removed)") }.text("Removes an MCP server from ${scope.agentId}")
    }

    override suspend fun execute(args: JsonObject): String {
        val s = own(args.requireString("name"))
        management.removeMcp(McpParams(scope.agentId, s.name, ConfigScope.Agent))
        return "Removed '${s.name}'."
    }

    private suspend fun own(name: String) = management.mcpServers(scope.agentId).firstOrNull { it.name == name && it.scope == ConfigScope.Agent }
        ?: throw IllegalArgumentException("No MCP server '$name' of your own; shared ones are removed by the user in Settings")
}

private class McpTest(management: AgentManagement, scope: CallerScope) : ManageTool(management, scope) {
    override val risk = Risk.Low
    override val repeatable = true
    override val descriptor = ToolDescriptor(
        name = "mcp_test",
        description = "Connect to one of your MCP servers and list the tools it offers, or say why it can't connect or whether it needs mcp_login.",
        requiredParameters = listOf(ToolParameterDescriptor("name", "The server's name from mcp_list", ToolParameterType.String)),
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

private class McpLogin(management: AgentManagement, scope: CallerScope) : ManageTool(management, scope) {
    override val risk = Risk.Medium
    override val descriptor = ToolDescriptor(
        name = "mcp_login",
        description = "Sign in to one of your HTTP MCP servers through OAuth. The user gets a sign-in link in a form, opens it, and pastes back the address their browser ends on; then the sign-in completes.",
        requiredParameters = listOf(ToolParameterDescriptor("name", "The server's name from mcp_list", ToolParameterType.String)),
    )

    override fun assess(args: JsonObject) = asks("Starts signing in to an MCP server", Risk.Medium)

    override suspend fun describe(args: JsonObject) = "Signs in to the MCP server '${args.requireString("name")}' of ${scope.agentId}; the user gets a link to open."

    override suspend fun execute(args: JsonObject) = run(args, null)
    override suspend fun execute(args: JsonObject, callId: String) = run(args, callId)

    private suspend fun run(args: JsonObject, callId: String?): String {
        val name = args.requireString("name")
        val url = management.loginMcp(McpLoginParams(scope.agentId, name))
        val question = Question("address", "Open this link and sign in: $url\n\nThen paste the address your browser ends on. It may show an error page; copy the address anyway.", QuestionKind.LongText, required = true)
        val answer = management.ask(scope, callId, "Sign in to $name", listOf(question))?.get("address")?.firstOrNull { it.isNotBlank() }
            ?: return "The user skipped the sign-in."
        return "Signed in to '${management.completeMcpLogin(McpLoginCompleteParams(answer.trim())).name}'."
    }
}

private class SessionConfigure(management: AgentManagement, scope: CallerScope) : ManageTool(management, scope) {
    override val risk = Risk.Medium
    override val descriptor = ToolDescriptor(
        name = "session_configure",
        description = "Change the model or reasoning effort of this Session only; your own defaults stay as they are. A Hidden Session keeps an on-device model. " +
            "You can make the Session stricter about Risk tolerance but never looser; the user raises it in Settings.",
        optionalParameters = listOf(
            ToolParameterDescriptor("provider", "Model provider; needs model", ToolParameterType.String),
            ToolParameterDescriptor("model", "A model of the provider if given, else of your current one", ToolParameterType.String),
            ToolParameterDescriptor("reasoning", "Reasoning effort", ToolParameterType.String),
            ToolParameterDescriptor("approvals", "A stricter Risk tolerance: strict, default or relaxed, never above the current one", ToolParameterType.String),
        ),
    )

    override fun assess(args: JsonObject) = asks("Changes this Session's model", Risk.Medium)

    override suspend fun describe(args: JsonObject): String {
        val (session, settings, changes) = plan(args)
        management.check(session.agentId, session.id, settings)
        return changes.text("Changes to this Session")
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
