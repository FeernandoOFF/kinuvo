package kinuvo.harness.server.testing

import kinuvo.harness.core.Agent
import kinuvo.harness.core.AgentConfig
import kinuvo.harness.core.AgentDoc
import kinuvo.harness.server.HarnessRuntime
import kinuvo.protocol.ConfigScope
import kinuvo.protocol.Note
import kinuvo.protocol.Routine
import kinuvo.protocol.SessionEntry
import kinuvo.protocol.SessionInfo
import kinuvo.protocol.TaskInfo
import kinuvo.protocol.TaskStatus
import kinuvo.protocol.Trigger
import kotlinx.datetime.LocalDate

/** The entries of a seeded Session's transcript, in order. */
class Transcript internal constructor() {
    internal val entries = mutableListOf<SessionEntry>()

    fun user(text: String) { entries += SessionEntry.UserMessage(text) }

    fun assistant(text: String, messageId: String? = "reply-${entries.size}") { entries += SessionEntry.AssistantMessage(text, messageId) }

    fun toolCall(id: String, name: String, args: String = "{}") { entries += SessionEntry.ToolCall(id, name, args) }

    fun toolResult(id: String, name: String, output: String, isError: Boolean = false) { entries += SessionEntry.ToolResult(id, name, output, isError) }

    fun entry(entry: SessionEntry) { entries += entry }
}

/**
 * Seeds a [TestServer] through the Server's own stores before clients connect, so a test starts from state the real
 * runtime wrote rather than replayed responses.
 */
class Scenario internal constructor(private val harness: HarnessRuntime) {
    /** Adds an Agent whose Identity names it [name]. */
    suspend fun agent(id: String, name: String = id, soul: String? = null, config: (AgentConfig) -> AgentConfig = { it }): Agent {
        val created = harness.store.createAgent(id)
        harness.docs.write(id, AgentDoc.Identity, "# $name\n")
        soul?.let { harness.docs.write(id, AgentDoc.Soul, it) }
        return harness.store.saveConfig(created, config(created.config)).copy(name = name)
    }

    /** Removes the Agent every fresh Server starts with, for scenarios that list only their own. */
    suspend fun withoutDefaultAgent() {
        harness.store.agents().firstOrNull { it.id == "default" }?.let { harness.store.deleteAgent(it) }
    }

    suspend fun session(agent: Agent, title: String? = null, favorite: Boolean = false, transcript: Transcript.() -> Unit = {}): SessionInfo {
        val session = harness.newSession(agent)
        val entries = Transcript().apply(transcript).entries
        if (entries.isNotEmpty()) harness.store.appendAll(agent.id, session.id, entries)
        if (title != null || favorite) harness.store.append(agent.id, session.id, SessionEntry.Details(title, favorite))
        return harness.store.session(agent.id, session.id)
    }

    /** A Board task; [list] is a list of the built-in task app ("inbox" by default). */
    suspend fun task(title: String, list: String? = null, status: TaskStatus = TaskStatus.Todo, assignee: String? = null, due: LocalDate? = null): TaskInfo {
        val created = harness.tasks.create(title, list, due = due, assignee = assignee)
        return if (status == TaskStatus.Todo) created else harness.tasks.update(created.id, status = status)
    }

    /** A Knowledge note; links in [body] read `[[title]]`. */
    suspend fun note(type: String, title: String, body: String = "", properties: Map<String, String?> = emptyMap()): Note =
        harness.knowledge.create(type, title, properties, body)

    suspend fun routine(agent: Agent, name: String, prompt: String, trigger: Trigger = Trigger.Every(60)): Routine =
        harness.routines.create(agent, name, prompt, trigger)

    suspend fun skill(agent: Agent, name: String, description: String, body: String, scope: ConfigScope = ConfigScope.Agent) =
        harness.skills.save(agent, scope, name, "---\nname: $name\ndescription: $description\n---\n$body")
}
