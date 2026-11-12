package kinuvo.testing.server

import kinuvo.harness.Agent
import kinuvo.harness.AgentConfig
import kinuvo.harness.AgentDoc
import kinuvo.server.HarnessRuntime
import kinuvo.server.execution.ExecutionRecovery
import kinuvo.server.execution.ExecutionRegistry
import kinuvo.server.execution.ExecutionRecoveryStore
import kinuvo.protocol.execution.AgentRegistration
import kinuvo.protocol.agent.ConfigScope
import kinuvo.protocol.execution.ExecutionCapabilities
import kinuvo.protocol.knowledge.Note
import kinuvo.protocol.routine.Routine
import kinuvo.protocol.session.SessionEntry
import kinuvo.protocol.session.SessionInfo
import kinuvo.protocol.board.TaskInfo
import kinuvo.protocol.board.TaskStatus
import kinuvo.protocol.routine.Trigger
import kinuvo.protocol.agent.AgentId
import kinuvo.protocol.session.ToolCallId
import kotlinx.datetime.LocalDate
import kotlinx.io.files.Path

/** The entries of a seeded Session's transcript, in order. */
class Transcript internal constructor() {
    internal val entries = mutableListOf<SessionEntry>()

    fun user(text: String) { entries += SessionEntry.UserMessage(text) }

    fun assistant(text: String, messageId: String? = "reply-${entries.size}") { entries += SessionEntry.AssistantMessage(text, messageId) }

    fun toolCall(id: String, name: String, args: String = "{}") { entries += SessionEntry.ToolCall(ToolCallId(id), name, args) }

    fun toolResult(id: String, name: String, output: String, isError: Boolean = false) { entries += SessionEntry.ToolResult(ToolCallId(id), name, output, isError) }

    fun entry(entry: SessionEntry) { entries += entry }
}

/**
 * Seeds a [TestServer] through the Server's own stores before clients connect, so a test starts from state the real
 * runtime wrote rather than replayed responses.
 */
class Scenario internal constructor(
    private val harness: HarnessRuntime,
    private val home: Path,
    private val executions: ExecutionRegistry,
    private val recovery: ExecutionRecoveryStore,
) {
    /** Adds an Agent whose Identity names it [name]. */
    suspend fun agent(id: String, name: String = id, soul: String? = null, config: (AgentConfig) -> AgentConfig = { it }): Agent {
        val created = harness.store.createAgent(id)
        harness.docs.write(AgentId(id), AgentDoc.Identity, "# $name\n")
        soul?.let { harness.docs.write(AgentId(id), AgentDoc.Soul, it) }
        return harness.store.saveConfig(created, config(created.config)).copy(name = name)
    }

    /** Removes the Agent every fresh Server starts with, for scenarios that list only their own. */
    suspend fun withoutDefaultAgent() {
        harness.store.agents().firstOrNull { it.id.value == "default" }?.let { harness.store.deleteAgent(it) }
    }

    suspend fun withoutAgent(agent: Agent) { harness.store.deleteAgent(agent) }

    suspend fun session(agent: Agent, title: String? = null, favorite: Boolean = false, transcript: Transcript.() -> Unit = {}): SessionInfo {
        val session = harness.newSession(agent)
        val entries = Transcript().apply(transcript).entries
        if (entries.isNotEmpty()) harness.store.appendAll(agent.id, session.id, entries)
        if (title != null || favorite) harness.store.append(agent.id, session.id, SessionEntry.Details(title, favorite))
        return harness.store.session(agent.id, session.id)
    }

    /** A Board task; [list] is a list of the built-in task app ("inbox" by default). */
    suspend fun task(
        title: String, list: String? = null, status: TaskStatus = TaskStatus.Todo, assignee: String? = null, due: LocalDate? = null,
        notes: String = "", sessions: List<SessionInfo> = emptyList(),
    ): TaskInfo {
        val created = harness.tasks.create(title, list, notes, due, assignee?.let(::AgentId))
        sessions.forEach { harness.tasks.started(created.id, it.id) }
        return if (status == TaskStatus.Todo) created else harness.tasks.update(created.id, status = status)
    }

    /** A Knowledge note; links in [body] read `[[title]]`. */
    suspend fun note(type: String, title: String, body: String = "", properties: Map<String, String?> = emptyMap()): Note =
        harness.knowledge.create(type, title, properties, body)

    suspend fun routine(agent: Agent, name: String, prompt: String, trigger: Trigger = Trigger.Every(60)): Routine =
        harness.routines.create(agent, name, prompt, trigger)

    suspend fun skill(agent: Agent, name: String, description: String, body: String, scope: ConfigScope = ConfigScope.Agent) =
        harness.skills.save(agent, scope, name, "---\nname: $name\ndescription: $description\n---\n$body")

    /** Registers [harness] as an installed harness connection, without any Agent on it. */
    suspend fun connection(harness: FakeHarness) { executions.save(harness.connection) }

    /** Registers [harness]'s connection and an external Agent on it, working in the Server's home. */
    suspend fun externalAgent(harness: FakeHarness, id: String, name: String = id): AgentRegistration {
        if (executions.catalog().connections.none { it.config.id == harness.id }) connection(harness)
        return executions.register(AgentRegistration(AgentId(id), name, harness.id, cwd = home.toString()))
    }

    /**
     * A Session of external Agent [agentId] whose harness connection ended while it ran: its [transcript] is saved, and
     * [draft] is the output that was never confirmed. [support] is what its harness reported, e.g. the file types it takes.
     */
    suspend fun lostExternalSession(
        agentId: AgentId,
        title: String? = null,
        draft: String? = null,
        support: ExecutionCapabilities = ExecutionCapabilities(cancel = true, questions = true),
        transcript: Transcript.() -> Unit = {},
    ): SessionInfo {
        val registration = requireNotNull(executions.registration(agentId)) { "No external Agent $agentId" }
        val info = harness.log.createSession(agentId, "")
        harness.log.appendAll(agentId, info.id, Transcript().apply(transcript).entries)
        if (title != null) harness.log.updateDetails(agentId, info.id, title, null)
        recovery.save(ExecutionRecovery(agentId, info.id, executions.configured(agentId), registration, "lost-${info.id}",
            externalSessionId = "fake-lost", support = support,
            draft = listOfNotNull(draft?.let { SessionEntry.AssistantMessage(it, "draft") })))
        return harness.log.session(agentId, info.id)
    }
}
