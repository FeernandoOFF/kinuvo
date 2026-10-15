package kinuvo.harness.core

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
fun capabilitiesFor(agent: Agent, tools: List<kinuvo.harness.core.tools.Tool>): String = buildString {
    val names = tools.map { it.identity?.localId ?: it.descriptor.name }.toSet()
    append("## Kinuvo capabilities for this run\n\n")
    append("This current capability summary, Workspace and Conventions supersede older snapshots in this Session.\n")
    append("You run in Kinuvo, a self-hosted personal AI harness. The Server owns Agents, Sessions and tools; paired Devices are clients.\n")
    append("Model provider: ${agent.config.provider}. Exec backend: ${agent.config.exec}.\n")
    append("Available tools: ${tools.map { it.descriptor.name }.sorted().joinToString()}. Use these tools to establish what is possible; do not assume unlisted integrations exist.\n")
    if ("memory" in names) append("The memory tool edits this Agent's Memory and User Notes. Their current contents appear in the personal context.\n")
    if ("knowledge" in names) append("Knowledge is the user's shared note system. Follow its Conventions. Asking for advice or options is not authorization to edit Notes.\n")
    if ("activate_skill" in names) append("Before specialized work, inspect the Current Skills catalog and activate a matching Skill with the Skill activation tool.\n")
    if ("read_skill_file" in names) append("Read an activated Skill's referenced resources with the Skill file tool when its instructions call for them.\n")
    if ("skill_manage" in names) append("When Skill instructions are outdated or contradicted by tool results, identify the concrete correction. Leave automatic learning to background Reflection, which proposes improvements for review; drafts are unavailable until approved. Explicit user requests to edit a Skill use the Skill management tool and the normal Approval policy.\n")
    if ("tasks" in names) append("Tasks live in the user's task provider and appear on the Board. Agents can work assigned Tasks; waiting on the user leaves the provider Task incomplete.\n")
    if ("schedule" in names) append("Routines schedule future Agent work on the Server; use the scheduling tool instead of claiming you will remember to act later.\n")
    if ("device_tools" in names) append("Device tools depend on paired Devices being available; check tool results for availability.\n")
    if ("run_code" in names) append("For many related tool calls (loops, filtering, chaining), write one Script with the Script tool instead of calling them one by one; only its return value comes back.\n")
    append("Tool calls follow the Session's Risk tolerance and Trust/Deny rules. Background Reflection stages Knowledge and learned Skills for explicit Approval regardless of Risk tolerance.\n")
}

fun skillCatalogFor(skills: List<kinuvo.harness.core.skills.Skill>): String =
    skills.filter { it.modelInvocable }.sortedBy { it.name }.joinToString("\n") { "- ${it.name}: ${it.description.replace('\n', ' ')}" }

/** Personal documents are refreshed in late context, leaving the instruction prefix stable. */
suspend fun personalContextFor(agent: Agent, docs: AgentDocs): String = buildString {
    append("## Current personal context\n\nThese Memory and User Notes supersede earlier snapshots in this Session.\n\n")
    append("### Memory\n\n${docs.read(agent.id, AgentDoc.Memory).trim()}\n\n")
    append("### User Notes\n\n${docs.read(agent.id, AgentDoc.UserNotes).trim()}\n")
}
