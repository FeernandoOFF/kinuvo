package kinuvo.server

import kinuvo.harness.Agent
import kinuvo.harness.AgentDoc
import kinuvo.harness.AgentDocs
import kinuvo.server.skills.Skill
import kinuvo.server.plugin.Tool
import kinuvo.protocol.tool.ToolNames
import kinuvo.protocol.capability.ResolvedTool

/** Builds the instructions snapshot frozen into a new Session. */
suspend fun instructionsFor(
    agent: Agent,
    docs: AgentDocs,
): String {
    val doc = listOf(AgentDoc.Identity, AgentDoc.Soul).associateWith { docs.read(agent.id, it) }
    return instructions(agent, doc)
}

private fun instructions(agent: Agent, doc: Map<AgentDoc, String>) = buildString {
    fun section(title: String, body: String) {
        if (body.isNotBlank()) append("## $title\n\n${body.trim()}\n\n")
    }
    append("You are ${agent.name}, a personal AI agent talking with your user.\n\n")
    section("How to work", OPERATING_INSTRUCTIONS)
    section("Identity", doc.getValue(AgentDoc.Identity))
    section("Soul", doc.getValue(AgentDoc.Soul))
}

private const val OPERATING_INSTRUCTIONS = """When the user asks you to do work, use your tools to do it; don't describe what you would do. When you say you will
act, make the tool call in the same response, and never end a turn with a promise of future action. Keep going until
the result is complete and verified, or a genuine blocker needs the user. Simple conversational answers need no tools.

Resolve retrievable missing information with tools. Ask only when a missing decision materially changes the work;
otherwise make a reasonable assumption and state it when it matters. Check current state and changeable facts with
tools rather than from memory. If a lookup is unexpectedly empty or partial, try another before concluding.
Request independent lookups together in one response; serialize only when a call depends on an earlier result.

Follow the user's authorized scope and Kinuvo's Approval decisions. Kinuvo handles tool Approval requests; proceed
with authorized work without repeatedly asking permission. Treat a denied action as a boundary. When a path fails,
try a reasonable alternative within scope. Never substitute plausible-looking output (invented data, file contents
or results) for what you couldn't actually produce; report the blocker instead. Claim success only when tool results
support it, and read back important changes when the write result doesn't confirm them.

Persist durable, confirmed information in one appropriate store: User Notes for facts and preferences about the
user; Memory for concise facts useful in every conversation; Skills for procedures and preferences specific to a
class of work; Knowledge for the user's world, following Conventions. Write entries as declarative facts ("User
prefers short replies"), not instructions to yourself. Current requests take precedence over remembered preferences.
Suggestions and silence are not agreement. Background Knowledge changes and learned Skills need the user's Approval."""

/** Derived from the tools of this run, rather than remembered product documentation. */
fun capabilitiesFor(agent: Agent, tools: List<Tool>, catalog: List<ResolvedTool> = emptyList(), compact: Boolean = false): String = buildString {
    append("## Kinuvo capabilities for this run\n\n")
    append("This current capability summary, Workspace and Conventions supersede older snapshots in this Session.\n")
    append("You run in Kinuvo, a self-hosted personal AI harness. The Server owns Agents, Sessions and tools; paired Devices are clients.\n")
    append("Model provider: ${agent.config.provider}. Exec backend: ${agent.config.exec}.\n")
    append("Available tools: ${tools.map { it.descriptor.name }.sorted().joinToString()}. Use these tools to establish what is possible; do not assume unlisted integrations exist.\n")
    tools.mapNotNull { it.coreMetadata }.distinct().filter { !compact || it.name !in CONFIGURATION_TOOLS }.forEach { append(if (compact) it.summary else it.hint ?: it.summary); append("\n") }
    append(notSetUpFor(catalog, tools))
    if (tools.any { it.descriptor.name == ToolNames.WEB_FETCH }) append("Kinuvo documentation: $KINUVO_DOCS_URL/llms.txt lists every page; fetch the matching page (add .md for plain text). Use web_fetch for questions about Kinuvo or how to set something up.\n")
    if (!compact) append("Tool calls follow the Session's Risk tolerance and Trust/Deny rules. Background Reflection stages Knowledge and learned Skills for explicit Approval regardless of Risk tolerance.\n")
}

fun skillCatalogFor(skills: List<Skill>): String =
    skills.filter { it.modelInvocable }.sortedBy { it.name }.joinToString("\n") { "- ${it.name}: ${it.description.replace('\n', ' ')}" }

/** Personal documents are refreshed in late context, leaving the instruction prefix stable. */
suspend fun personalContextFor(agent: Agent, docs: AgentDocs): String = buildString {
    append("## Current personal context\n\nThese Memory and User Notes supersede earlier snapshots in this Session.\n\n")
    append("### Memory\n\n${docs.read(agent.id, AgentDoc.Memory).trim()}\n\n")
    append("### User Notes\n\n${docs.read(agent.id, AgentDoc.UserNotes).trim()}\n")
}

private const val IDENTITY_CHARS = 600

/** The whole prompt for a small model: who it is, what it knows about the user, and its tools; with [identity], the start of the Agent's Identity too. */
suspend fun basicContextFor(agent: Agent, docs: AgentDocs, tools: List<Tool>, today: String, identity: Boolean = false): String = buildString {
    val known = listOf(AgentDoc.UserNotes, AgentDoc.Memory).map { docs.read(agent.id, it).trim() }.filter { it.isNotEmpty() }
    append("You are ${agent.name}, the user's personal assistant. Keep replies short and friendly.\n\n")
    if (identity) docs.read(agent.id, AgentDoc.Identity).trim().take(IDENTITY_CHARS).takeIf { it.isNotEmpty() }?.let { append("Who you are:\n$it\n\n") }
    if (known.isNotEmpty()) append("What you know about the user:\n${known.joinToString("\n")}\n\n")
    if (tools.isNotEmpty()) append("Tools: ${tools.joinToString { it.identity?.localId ?: it.descriptor.name }}. ")
    append("Use a tool when the user asks you to do something, and only say it's done after the tool succeeds.")
    if (tools.any { it.identity?.localId == ToolNames.MEMORY }) append(" To remember something about the user, use the memory tool.")
    append("\nToday is $today.\n")
}

private val CONFIGURATION_TOOLS = setOf(ToolNames.AGENT_GET, ToolNames.AGENT_UPDATE, ToolNames.AGENT_CREATE,
    ToolNames.MCP_LIST, ToolNames.MCP_ADD, ToolNames.MCP_REMOVE, ToolNames.MCP_TEST, ToolNames.MCP_LOGIN, ToolNames.SESSION_CONFIGURE)

const val KINUVO_DOCS_URL = "https://kinuvo.feernandooff.com"

suspend fun compactContextFor(agent: Agent, docs: AgentDocs): String =
    "You are ${agent.name}, the user's personal assistant.\n\n" +
        "When asked to act, use tools. Never claim success without a tool result.\n" +
        "Ask only when a missing decision materially changes the work.\n" +
        "Keep replies short.\n\n" + personalContextFor(agent, docs)

fun aboutKinuvoFor(tools: List<Tool>): String = buildString {
    val names = tools.map { it.descriptor.name }.toSet()
    append("## About Kinuvo\n\n")
    append("Kinuvo is a self-hosted personal AI harness. Its Server owns Agents, Sessions and Tools; paired Devices are clients. An Agent is a named participant; a Session is one bounded conversation. Tools read or change things under Kinuvo's Approvals.\n")
    if (ToolNames.MCP_LIST in names) append("MCP servers connect additional tools; use mcp_list to see your configured connections.\n")
    if (ToolNames.ACTIVATE_SKILL in names) append("Skills are named instructions and reference files; activate only Skills listed in the current catalog.\n")
    if (ToolNames.SCHEDULE in names) append("Routines run an Agent's prompt on a schedule; use schedule for future work.\n")
    if (ToolNames.AGENT_GET in names) append("Use agent_get to inspect your own Agent settings before proposing changes.\n")
    if (ToolNames.AGENT_UPDATE in names) append("Use agent_update to change your own Identity, Soul, model or enabled tools.\n")
    if (ToolNames.AGENT_CREATE in names) append("Use agent_create to create a blank Agent.\n")
    if (ToolNames.MCP_ADD in names) append("Use mcp_add to connect an MCP server for your Agent.\n")
    if (ToolNames.SESSION_CONFIGURE in names) append("Use session_configure for this Session's model, reasoning or stricter Risk tolerance.\n")
    if (names.any { it in setOf(ToolNames.AGENT_UPDATE, ToolNames.AGENT_CREATE, ToolNames.MCP_ADD, ToolNames.MCP_REMOVE, ToolNames.SESSION_CONFIGURE) })
        append("Say what you will change before calling a configuration tool; required Approvals show the change. Chat never loosens safety.\n")
    if (ToolNames.AGENT_UPDATE !in names) append("For model changes, open the selected Agent's Personality page. For tool changes, use Server settings → Tools & MCP → Built-in tools.\n")
    if (ToolNames.MCP_ADD !in names) append("For MCP connections, use Server settings → Tools & MCP → MCP servers.\n")
    append("Never answer questions about Kinuvo from memory: consult the docs.")
    if (ToolNames.WEB_FETCH !in names) append(" Point the user to Settings or the documentation at $KINUVO_DOCS_URL when you cannot consult it here.")
    append("\n")
}

private fun notSetUpFor(catalog: List<ResolvedTool>, tools: List<Tool>): String {
    val paths = catalog.filter { it.state.reasonCode == "needs_setup" && it.state.docsPath != null }
        .map { it.state.docsPath!! }.distinct()
    if (paths.isEmpty()) return ""
    val missing = paths.joinToString { path ->
        val name = when (path) {
            "guides/computer-use/" -> "Computer use"
            "guides/browser-access/" -> "Browser"
            else -> catalog.first { it.state.docsPath == path }.name
        }
        "$name (${path.trimEnd('/')})"
    }
    val guidance = if (tools.any { it.descriptor.name == ToolNames.WEB_FETCH })
        "If the user asks for one, fetch its page from the docs and guide them; do not try the tool."
    else "If the user asks for one, point them to Settings/docs; do not try the tool."
    return "Not set up: $missing. $guidance\n"
}
