package kinuvo.harness.core.store

import kinuvo.harness.core.Agent
import kinuvo.harness.core.AgentConfig
import kinuvo.harness.core.AgentDoc
import kinuvo.harness.core.identityName
import kinuvo.protocol.RoutineRef
import kinuvo.protocol.SessionEntry
import kinuvo.protocol.SessionOrder
import kinuvo.protocol.SessionInfo
import kinuvo.protocol.SessionSettings
import kinuvo.protocol.TrustRules
import kinuvo.protocol.parseTaskPrompt
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format.char
import kotlinx.datetime.toInstant
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
        files.agents.children().filter { AGENT_ID.matches(it.name) && isInside(files.agents, it) && files.config(it.name).isFile() }.map { load(it.name) }

    override suspend fun createAgent(id: String, from: Agent?): Agent {
        require(AGENT_ID.matches(id)) { "Agent IDs use lowercase letters, digits and hyphens, up to 32 characters" }
        require(!files.agent(id).exists()) { "Agent '$id' already exists" }
        if (from == null) writeTemplate(id)
        else {
            require(AGENT_ID.matches(from.id)) { "Invalid source Agent ID" }
            require(isInside(files.agents, files.agent(from.id))) { "Source Agent path escapes storage" }
            files.agent(id).createDirectories()
            cloned(from.id).zip(cloned(id)).filter { it.first.exists() }.forEach { (src, dst) -> copyTree(src, dst) }
            listOf(AgentDoc.Memory, AgentDoc.UserNotes).forEach { writeAtomically(files.doc(id, it), "") }
            files.updateAgentConfig(id) { it.copy(rules = TrustRules()) }
        }
        return load(id)
    }

    override suspend fun deleteAgent(agent: Agent) {
        require(AGENT_ID.matches(agent.id)) { "Not an Agent directory" }
        require(isInside(files.agents, files.agent(agent.id))) { "Agent path escapes storage" }
        deleteTree(files.agent(agent.id))
    }

    override suspend fun saveConfig(agent: Agent, config: AgentConfig): Agent {
        require(AGENT_ID.matches(agent.id)) { "Invalid Agent ID" }
        require(isInside(files.agents, files.config(agent.id))) { "Agent path escapes storage" }
        return agent.copy(config = files.updateAgentConfig(agent.id) { config.copy(rules = it.rules) })
    }

    // Lists every file and reads each title; a DB indexes Sessions and pages them with a query.
    override suspend fun sessions(agentId: String): List<SessionInfo> =
        sessionDirectory(agentId).children().filter { it.name.endsWith(".jsonl") }
            .sortedByDescending { it.name }
            .mapIndexed { index, file -> session(agentId, file.name.removeSuffix(".jsonl")).let {
                if (it.createdAt == null) it.copy(legacyOrder = index.toLong()) else it
            } }.sortedWith(SessionOrder)

    override suspend fun session(agentId: String, sessionId: String): SessionInfo {
        var routine: RoutineRef? = null
        var settings = SessionSettings()
        val entries = entries(agentId, sessionId)
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
        return SessionInfo(sessionId, agentId, details.title ?: routine?.name ?: title ?: NEW_TITLE, routine = routine, settings = settings,
            answered = answered, task = task, favorite = details.favorite, createdAt = createdAt(sessionId))
    }

    override suspend fun createSession(agentId: String, instructions: String, routine: RoutineRef?): SessionInfo {
        val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
        val id = ID_FORMAT.format(now) + "-" + Random.nextInt(0x1000, 0xffff).toString(16)
        lock.withLock {
            require(!file(agentId, id).exists()) { "Session already exists" }
            sessionDirectory(agentId).createDirectories()
            writeAtomically(file(agentId, id), encode(listOf(SessionEntry.Started(instructions, routine))))
        }
        return SessionInfo(id, agentId, routine?.name ?: NEW_TITLE, routine = routine, createdAt = createdAt(id))
    }

    override suspend fun append(agentId: String, sessionId: String, entry: SessionEntry) = lock.withLock {
        entries(agentId, sessionId) // Refuse to append after a corrupt or torn record.
        write(agentId, sessionId, entry)
    }

    override suspend fun appendAll(agentId: String, sessionId: String, entries: List<SessionEntry>): List<SessionEntry> = lock.withLock {
        val saved = this.entries(agentId, sessionId)
        val added = missingEntries(saved, entries)
        if (added.isNotEmpty()) writeAtomically(file(agentId, sessionId), encode(saved + added))
        added
    }

    private fun encode(entries: List<SessionEntry>) = entries.joinToString("") { json.encodeToString(SessionEntry.serializer(), it) + "\n" }

    private fun write(agentId: String, sessionId: String, entry: SessionEntry) {
        val file = file(agentId, sessionId)
        val text = file.readTextOrNull().orEmpty()
        val separator = if (text.isNotEmpty() && !text.endsWith("\n")) "\n" else ""
        file.appendText(separator + json.encodeToString(SessionEntry.serializer(), entry) + "\n")
    }

    override suspend fun entries(agentId: String, sessionId: String): List<SessionEntry> =
        readEntries(file(agentId, sessionId), sessionId)

    override suspend fun deleteSession(agentId: String, sessionId: String) = lock.withLock {
        SystemFileSystem.delete(file(agentId, sessionId), mustExist = false)
    }

    override suspend fun truncate(agentId: String, sessionId: String, size: Int) = lock.withLock {
        val file = file(agentId, sessionId)
        require(size >= 0)
        val saved = readEntries(file, sessionId)
        if (saved.size > size) writeAtomically(file, encode(saved.take(size)))
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

    private fun readEntries(file: Path, sessionId: String): List<SessionEntry> {
        val text = file.readTextOrNull() ?: return emptyList()
        val lines = text.lines()
        return lines.mapIndexedNotNull { index, line ->
            if (line.isBlank()) null else try {
                json.decodeFromString(SessionEntry.serializer(), line)
            } catch (e: Exception) {
                throw CorruptSession(sessionId, index + 1, index == lines.lastIndex && !text.endsWith("\n"), e)
            }
        }
    }

    private fun createdAt(sessionId: String): Long? {
        if (!NATIVE_SESSION_ID.matches(sessionId)) return null
        return runCatching {
            LocalDateTime.parse(sessionId.take(15), ID_FORMAT).toInstant(TimeZone.currentSystemDefault()).toEpochMilliseconds()
        }.getOrNull()
    }

    private fun sessionDirectory(agentId: String): Path {
        validateOwnerId(agentId)
        return files.sessions(agentId).also { require(isInside(files.agents, it)) { "Session directory escapes storage" } }
    }

    private fun file(agentId: String, sessionId: String): Path {
        validateSessionId(sessionId)
        val directory = sessionDirectory(agentId)
        val path = Path(directory, "$sessionId.jsonl")
        require(isInside(directory, path)) { "Session path escapes owner directory" }
        // Presence, run and subscription registries use globally unique Session IDs.
        require(files.agents.children().none { owner ->
            owner.name != agentId && Path(owner, "sessions", "$sessionId.jsonl").exists()
        }) { "Session ID '$sessionId' belongs to another owner" }
        return path
    }

    companion object {
        const val NEW_TITLE = "New session"
        private val ID_FORMAT = LocalDateTime.Format {
            year(); monthNumber(); day(); char('-'); hour(); minute(); second()
        }
        private val NATIVE_SESSION_ID = Regex("[0-9]{8}-[0-9]{6}-[0-9a-f]{4}")
        private val AGENT_ID = Regex("[a-z0-9][a-z0-9-]{0,31}")
    }
}
