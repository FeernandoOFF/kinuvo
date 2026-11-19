package kinuvo.server

import kinuvo.harness.Agent
import kinuvo.harness.AgentDoc
import kinuvo.harness.AgentDocs
import kinuvo.server.skills.Skill
import kinuvo.server.plugin.Tool
import kinuvo.protocol.approval.Risk
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

internal const val PROACTIVITY_BRIEF = """When the user names a topic, goal, problem or open-ended request, research first with available tools: web search, connected apps, notes and Memory.
Research only with relevant read-only calls within the user's scope, and keep private data (Memory, notes, connector content) out of web-search queries and other external tool arguments unless the user asked for that disclosure.
Answer with findings and a recommendation, not questions or a menu. Do read-only follow-ups before offering more; never end read-only work with "want me to...?".
Ask or offer choices before writes, sends, spending or irreversible steps outside the user's authorized scope; follow Kinuvo's Approvals without asking again for authorized work.
Ask one focused question only when a missing decision materially changes the work, after doing the research possible without it.
Never answer from memory alone when you can look it up with tools."""

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
    tools.coreName(ToolNames.WEB_FETCH)?.let { webFetch -> append("Kinuvo documentation: $KINUVO_DOCS_URL/llms.txt lists the owner documentation; fetch the matching URL exactly as listed. Use $webFetch for questions about Kinuvo or how to set something up.\n") }
    if (!compact) append("Tool calls follow the Session's Risk tolerance and Trust/Deny rules. Background Reflection stages Knowledge and learned Skills for explicit Approval regardless of Risk tolerance.\n")
}

fun skillCatalogFor(skills: List<Skill>): String =
    skills.filter { it.modelInvocable }.sortedBy { it.name }.joinToString("\n") { "- ${it.name}: ${it.description.replace('\n', ' ')}" }

/** Personal documents are refreshed in late context, leaving the instruction prefix stable. */
suspend fun personalContextFor(agent: Agent, docs: AgentDocs): String =
    personalContextFor(docs.read(agent.id, AgentDoc.Memory), docs.read(agent.id, AgentDoc.UserNotes))

fun personalContextFor(memory: String, userNotes: String): String = buildString {
    append("## Current personal context\n\nThese Memory and User Notes supersede earlier snapshots in this Session.\n\n")
    append("### Memory\n\n${memory.trim()}\n\n")
    append("### User Notes\n\n${userNotes.trim()}\n")
}

fun firstSessionFor(tools: List<Tool>): String = buildString {
    fun name(id: String) = tools.coreName(id)
    append("## First session\n\n")
    append("Do a concrete user task first; offer discovery afterwards. Otherwise introduce yourself, say in one short sentence what you will inspect, then act without asking permission.\n")
    if (tools.any { it.coreMetadata == null && it.identity?.instance?.pluginId == "kinuvo.mcp" && it.risk == Risk.Low }) {
        name(ToolNames.MCP_LIST)?.let { append("Use $it to see connected servers. ") }
        append("Explore only their advertised read-only tools (events, issues, mail subjects); never write, send or delete. Approvals still apply. Make at most 8 cheap look-ups; stop earlier when enough.\n")
        append("Lead with what you found and your top recommendation in plain language; invite corrections. Then propose 3–5 concrete next steps")
        when {
            name(ToolNames.SHOW_UI) != null -> append(" in a compact ${name(ToolNames.SHOW_UI)} card")
            name(ToolNames.ASK_USER) != null -> append(" with ${name(ToolNames.ASK_USER)}")
            else -> append(" as a numbered list")
        }
        append(". Do the first suggested read-only follow-up yourself when cheap, within the look-up limit.")
        if (name(ToolNames.TASKS) != null) append(" Offer Add to tasks actions using ${name(ToolNames.TASKS)} only after the user chooses.")
        append("\nIf no connected server offers read-only look-up tools, skip look-ups and do the no-apps introduction: ")
    } else append("Skip look-ups: ")
    append("introduce yourself in 1–2 sentences, ask what the user wants help with, and mention connecting apps following the connector guidance.\n")
    append("During discovery, do not record connector offers or decline/ignore events in Memory or User Notes. User Notes may only receive durable facts the user stated or confirmed, never guesses or silence.\n")
    if (name(ToolNames.MCP_ADD) == null || name(ToolNames.MCP_LOGIN) == null) append("For adding apps or sign-in, point to Settings → Server settings → Tools & MCP → MCP servers.\n")
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
        "Resolve retrievable missing information with tools; check current state and changeable facts with tools rather than from memory.\n" +
        "If a lookup is unexpectedly empty or partial, try another before concluding.\n" +
        "Keep going until the result is complete and verified or genuinely blocked; never end a turn with a promise of future action.\n" +
        "$PROACTIVITY_BRIEF\n" +
        "Keep replies short.\n\n" + personalContextFor(agent, docs)

fun aboutKinuvoFor(tools: List<Tool>, discovery: Boolean = false): String = buildString {
    val names = tools.mapNotNull { it.coreMetadata?.name }.toSet()
    fun name(id: String) = tools.coreName(id)
    val canLogin = ToolNames.MCP_LOGIN in names && ToolNames.ASK_USER in names
    append("## About Kinuvo\n\n")
    append("Kinuvo is a self-hosted personal AI harness. Its Server owns Agents, Sessions and Tools; paired Devices are clients. An Agent is a named participant; a Session is one bounded conversation. Tools read or change things under Kinuvo's Approvals.\n")
    if (ToolNames.MCP_LIST in names) {
        append("When a request would benefit from an unconfigured connection (tasks: Todoist/Linear; mail: Gmail; notes: Notion), check ${name(ToolNames.MCP_LIST)}'s available presets and offer the relevant one once. Research the user's request with existing tools without waiting for a connection decision. ")
        when {
            ToolNames.SHOW_UI in names -> append("Present a small ${name(ToolNames.SHOW_UI)} card with Add / Not now. ")
            ToolNames.ASK_USER in names -> append("Present the offer with ${name(ToolNames.ASK_USER)}. ")
            else -> append("Ask in plain text. ")
        }
        if (!discovery && ToolNames.MEMORY in names) append("Only after an explicit decline (the user says no or chooses Not now), note it in Memory with ${name(ToolNames.MEMORY)} so you do not offer it again; never record silence. ")
        if (ToolNames.MCP_ADD in names) {
            append("On yes, use ${name(ToolNames.MCP_ADD)}(preset)")
            if (canLogin) append(" then ${name(ToolNames.MCP_LOGIN)} and finish sign-in with the user")
            else append(" and guide sign-in in Server settings → Tools & MCP → MCP servers")
            append("; presets needing credentials require the user's setup in Settings. ")
        } else append("On yes, guide the user to Server settings → Tools & MCP → MCP servers. ")
        if (canLogin) append("If a configured server's tool fails with an auth/sign-in error, say so and offer to reconnect via ${name(ToolNames.MCP_LOGIN)}. ")
        else append("For sign-in or reconnection, guide the user to Settings → Server settings → Tools & MCP → MCP servers. ")
        append("\n")
    }
    if (ToolNames.ACTIVATE_SKILL in names) append("Skills are named instructions and reference files; activate only Skills listed in the current catalog.\n")
    if (ToolNames.SCHEDULE in names) append("Routines run an Agent's prompt on a schedule; use ${name(ToolNames.SCHEDULE)} for future work.\n")
    if (ToolNames.AGENT_GET in names) append("Use ${name(ToolNames.AGENT_GET)} to inspect your own Agent settings before proposing changes.\n")
    if (ToolNames.AGENT_UPDATE in names) append("Use ${name(ToolNames.AGENT_UPDATE)} to change your own Identity, Soul, model or enabled tools.\n")
    if (ToolNames.AGENT_CREATE in names) append("Use ${name(ToolNames.AGENT_CREATE)} to create a blank Agent.\n")
    if (ToolNames.MCP_ADD in names) append("Use ${name(ToolNames.MCP_ADD)} to connect an MCP server for your Agent.\n")
    if (ToolNames.SESSION_CONFIGURE in names) append("Use ${name(ToolNames.SESSION_CONFIGURE)} for this Session's model, reasoning or stricter Risk tolerance.\n")
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
    val guidance = if (tools.coreName(ToolNames.WEB_FETCH) != null)
        "If the user asks for one, fetch its page from the docs and guide them; do not try the tool."
    else "If the user asks for one, point them to Settings/docs; do not try the tool."
    return "Not set up: $missing. $guidance\n"
}

private fun List<Tool>.coreName(id: String) = firstOrNull { it.coreMetadata?.name == id }?.descriptor?.name
