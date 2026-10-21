package kinuvo.harness.core.store

import kinuvo.harness.core.Agent
import kinuvo.harness.core.AgentConfig
import kinuvo.harness.core.AgentDoc
import kinuvo.harness.core.identityName
import kinuvo.protocol.RoutineRef
import kinuvo.protocol.SessionEntry
import kinuvo.protocol.SessionInfo
import kinuvo.protocol.SessionSettings
import kinuvo.protocol.TrustRules
import kinuvo.protocol.parseTaskPrompt
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format.char
import kotlinx.datetime.toLocalDateTime
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlin.random.Random
import kotlin.time.Clock

/** `<home>/agents/<id>/` directories, Sessions as `sessions/<id>.jsonl`. */
class FileStore(private val files: AgentFiles) : Store {
    /** Session writes go one at a time, so a truncate can't drop an append and JSONL lines don't interleave. */
    private val lock = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    init {
        if (files.agents.children().none { it.isDirectory() }) writeTemplate("default")
    }

    override suspend fun agents(): List<Agent> =
        files.agents.children().filter { files.config(it.name).isFile() }.map { load(it.name) }

    override suspend fun createAgent(id: String, from: Agent?): Agent {
        require(AGENT_ID.matches(id)) { "Agent IDs use lowercase letters, digits and hyphens, up to 32 characters" }
        require(!files.agent(id).exists()) { "Agent '$id' already exists" }
        if (from == null) writeTemplate(id)
        else {
            files.agent(id).createDirectories()
            cloned(from.id).zip(cloned(id)).filter { it.first.exists() }.forEach { (src, dst) -> copyTree(src, dst) }
            listOf(AgentDoc.Memory, AgentDoc.UserNotes).forEach { writeAtomically(files.doc(id, it), "") }
            files.updateAgentConfig(id) { it.copy(rules = TrustRules()) }
        }
        return load(id)
    }

    override suspend fun deleteAgent(agent: Agent) {
        require(AGENT_ID.matches(agent.id)) { "Not an Agent directory" }
        deleteTree(files.agent(agent.id))
    }

    override suspend fun saveConfig(agent: Agent, config: AgentConfig): Agent =
        agent.copy(config = files.updateAgentConfig(agent.id) { config.copy(rules = it.rules) })

    // Lists every file and reads each title; a DB indexes Sessions and pages them with a query.
    override suspend fun sessions(agent: Agent): List<SessionInfo> =
        files.sessions(agent.id).children().filter { it.name.endsWith(".jsonl") }
            .sortedByDescending { it.name }
            .map { session(agent, it.name.removeSuffix(".jsonl")) }

    override suspend fun session(agent: Agent, sessionId: String): SessionInfo {
        var routine: RoutineRef? = null
        var settings = SessionSettings()
        val entries = file(agent, sessionId).readTextOrNull()?.lineSequence()
            ?.mapNotNull { runCatching { json.decodeFromString(SessionEntry.serializer(), it) }.getOrNull() }.orEmpty()
        var title: String? = null
        var task: String? = null
        var answered = false
        var details = SessionEntry.Details()
        for (e in entries) when (e) {
            is SessionEntry.Outcome -> answered = answered || e.report
            is SessionEntry.Started -> routine = e.routine
            is SessionEntry.Settings -> settings = e.settings
            is SessionEntry.Details -> details = e
            is SessionEntry.UserMessage -> {
                answered = answered || e.routine == null
                if (title == null) {
                    val run = parseTaskPrompt(e.text)
                    task = run?.first
                    title = (run?.second ?: e.text.ifBlank { e.attachments.firstOrNull()?.name.orEmpty() }).take(60)
                }
            }
            else -> Unit
        }
        return SessionInfo(sessionId, agent.id, details.title ?: routine?.name ?: title ?: NEW_TITLE, routine = routine, settings = settings,
            answered = answered, task = task, favorite = details.favorite)
    }

    override suspend fun createSession(agent: Agent, instructions: String, routine: RoutineRef?): SessionInfo {
        val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
        val id = ID_FORMAT.format(now) + "-" + Random.nextInt(0x1000, 0xffff).toString(16)
        lock.withLock { write(agent, id, SessionEntry.Started(instructions, routine)) }
        return SessionInfo(id, agent.id, routine?.name ?: NEW_TITLE, routine = routine)
    }

    override suspend fun append(agent: Agent, sessionId: String, entry: SessionEntry) = lock.withLock { write(agent, sessionId, entry) }

    private fun write(agent: Agent, sessionId: String, entry: SessionEntry) =
        file(agent, sessionId).appendText(json.encodeToString(SessionEntry.serializer(), entry) + "\n")

    override suspend fun entries(agent: Agent, sessionId: String): List<SessionEntry> =
        lines(file(agent, sessionId)).map { json.decodeFromString(SessionEntry.serializer(), it) }

    override suspend fun deleteSession(agent: Agent, sessionId: String) = lock.withLock {
        SystemFileSystem.delete(file(agent, sessionId), mustExist = false)
    }

    override suspend fun truncate(agent: Agent, sessionId: String, size: Int) = lock.withLock {
        val file = file(agent, sessionId)
        val lines = lines(file)
        if (lines.size > size) writeAtomically(file, lines.take(size).joinToString("") { "$it\n" })
    }

    private fun load(id: String): Agent {
        return Agent(id, files.doc(id, AgentDoc.Identity).readTextOrNull()?.let(::identityName) ?: id, files.agentConfig(id))
    }

    private fun writeTemplate(id: String) {
        writeAtomically(files.config(id), Templates.config)
        Templates.docs.forEach { (doc, text) -> writeAtomically(files.doc(id, doc), text) }
    }

    private fun cloned(id: String) = listOf(
        files.config(id), files.doc(id, AgentDoc.Identity), files.doc(id, AgentDoc.Soul), files.skills(id), files.mcp(id), files.env(id),
    )

    private fun lines(file: Path) = file.readTextOrNull()?.lines()?.filter { it.isNotBlank() }.orEmpty()

    private fun file(agent: Agent, sessionId: String): Path {
        require(SESSION_ID.matches(sessionId)) { "Invalid session ID" }
        return Path(files.sessions(agent.id), "$sessionId.jsonl")
    }

    companion object {
        const val NEW_TITLE = "New session"
        private val ID_FORMAT = LocalDateTime.Format {
            year(); monthNumber(); day(); char('-'); hour(); minute(); second()
        }
        private val SESSION_ID = Regex("[A-Za-z0-9_-]{1,128}")
        private val AGENT_ID = Regex("[a-z0-9][a-z0-9-]{0,31}")
    }
}
