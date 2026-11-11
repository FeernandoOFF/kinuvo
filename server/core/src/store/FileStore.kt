package kinuvo.server.store

import kinuvo.server.plugin.appendText
import kinuvo.server.plugin.children
import kinuvo.server.plugin.copyTree
import kinuvo.server.plugin.createDirectories
import kinuvo.server.plugin.deleteTree
import kinuvo.server.plugin.exists
import kinuvo.server.plugin.isDirectory
import kinuvo.server.plugin.isFile
import kinuvo.server.plugin.isInside
import kinuvo.server.plugin.readTextOrNull
import kinuvo.server.plugin.writeAtomically
import kinuvo.harness.Agent
import kinuvo.harness.AgentConfig
import kinuvo.harness.AgentDoc
import kinuvo.harness.identityName
import kinuvo.protocol.routine.RoutineRef
import kinuvo.protocol.session.SessionEntry
import kinuvo.protocol.session.withWrittenAt
import kinuvo.protocol.session.SessionOrder
import kinuvo.protocol.session.SessionInfo
import kinuvo.protocol.session.SessionSettings
import kinuvo.protocol.approval.TrustRules
import kinuvo.protocol.session.parseTaskPrompt
import kinuvo.protocol.agent.AgentId
import kinuvo.protocol.session.SessionId
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
import kotlin.time.Instant
import kotlin.time.Clock

/** `<home>/agents/<id>/` directories, Sessions as `sessions/<id>.jsonl`. */
class FileStore(
    private val files: AgentFiles,
    private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val random: Random = Random,
) : Store {
    /** Session writes go one at a time, so a truncate can't drop an append and JSONL lines don't interleave. */
    private val lock = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    init {
        if (files.agents.children().none { it.isDirectory() }) writeTemplate("default")
    }

    override suspend fun agents(): List<Agent> =
        files.agents.children().filter { AGENT_ID.matches(it.name) && isInside(files.agents, it) && files.config(AgentId(it.name)).isFile() }.map { load(it.name) }

    override suspend fun createAgent(id: String, from: Agent?): Agent {
        require(AGENT_ID.matches(id)) { "Agent IDs use lowercase letters, digits and hyphens, up to 32 characters" }
        require(!files.agent(AgentId(id)).exists()) { "Agent '$id' already exists" }
        if (from == null) writeTemplate(id)
        else {
            require(AGENT_ID.matches(from.id.value)) { "Invalid source Agent ID" }
            require(isInside(files.agents, files.agent(from.id))) { "Source Agent path escapes storage" }
            files.agent(AgentId(id)).createDirectories()
            cloned(from.id.value).zip(cloned(id)).filter { it.first.exists() }.forEach { (src, dst) -> copyTree(src, dst) }
            listOf(AgentDoc.Memory, AgentDoc.UserNotes).forEach { writeAtomically(files.doc(AgentId(id), it), "") }
            files.updateAgentConfig(AgentId(id)) { it.copy(rules = TrustRules()) }
        }
        return load(id)
    }

    override suspend fun deleteAgent(agent: Agent) {
        require(AGENT_ID.matches(agent.id.value)) { "Not an Agent directory" }
        require(isInside(files.agents, files.agent(agent.id))) { "Agent path escapes storage" }
        deleteTree(files.agent(agent.id))
    }

    override suspend fun saveConfig(agent: Agent, config: AgentConfig): Agent {
        require(AGENT_ID.matches(agent.id.value)) { "Invalid Agent ID" }
        require(isInside(files.agents, files.config(agent.id))) { "Agent path escapes storage" }
        return agent.copy(config = files.updateAgentConfig(agent.id) { config.copy(rules = it.rules) })
    }

    // Lists every file and reads each title; a DB indexes Sessions and pages them with a query.
    override suspend fun sessions(agentId: AgentId): List<SessionInfo> =
        sessionDirectory(agentId).children().filter { it.name.endsWith(".jsonl") }
            .sortedByDescending { it.name }
            .mapIndexed { index, file -> session(agentId, SessionId(file.name.removeSuffix(".jsonl"))).let {
                if (it.createdAt == null) it.copy(legacyOrder = index.toLong()) else it
            } }.sortedWith(SessionOrder)

    override suspend fun session(agentId: AgentId, sessionId: SessionId): SessionInfo {
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

    override suspend fun createSession(agentId: AgentId, instructions: String, routine: RoutineRef?): SessionInfo {
        val now = Instant.fromEpochMilliseconds(clock()).toLocalDateTime(TimeZone.currentSystemDefault())
        val prefix = ID_FORMAT.format(now) + "-"
        val id = lock.withLock {
            sessionDirectory(agentId).createDirectories()
            val id = generateSequence { prefix + random.nextInt(0x1000, 0xffff).toString(16) }.first { !taken(SessionId(it)) }
            writeAtomically(file(agentId, SessionId(id)), encode(listOf(SessionEntry.Started(instructions, routine, writtenAt = clock()))))
            id
        }
        return SessionInfo(SessionId(id), agentId, routine?.name ?: NEW_TITLE, routine = routine, createdAt = createdAt(SessionId(id)))
    }

    override suspend fun append(agentId: AgentId, sessionId: SessionId, entry: SessionEntry) = lock.withLock {
        entries(agentId, sessionId) // Refuse to append after a corrupt or torn record.
        write(agentId, sessionId, entry.withWrittenAt(entry.writtenAt ?: clock()))
    }

    override suspend fun appendAll(agentId: AgentId, sessionId: SessionId, entries: List<SessionEntry>): List<SessionEntry> = lock.withLock {
        val saved = this.entries(agentId, sessionId)
        val added = missingEntries(saved, entries).map { it.withWrittenAt(it.writtenAt ?: clock()) }
        if (added.isNotEmpty()) writeAtomically(file(agentId, sessionId), encode(saved + added))
        added
    }

    private fun encode(entries: List<SessionEntry>) = entries.joinToString("") { json.encodeToString(SessionEntry.serializer(), it) + "\n" }

    private fun write(agentId: AgentId, sessionId: SessionId, entry: SessionEntry) {
        val file = file(agentId, sessionId)
        val text = file.readTextOrNull().orEmpty()
        val separator = if (text.isNotEmpty() && !text.endsWith("\n")) "\n" else ""
        file.appendText(separator + json.encodeToString(SessionEntry.serializer(), entry) + "\n")
    }

    override suspend fun entries(agentId: AgentId, sessionId: SessionId): List<SessionEntry> =
        readEntries(file(agentId, sessionId), sessionId)

    override suspend fun deleteSession(agentId: AgentId, sessionId: SessionId) = lock.withLock {
        SystemFileSystem.delete(file(agentId, sessionId), mustExist = false)
    }

    override suspend fun truncate(agentId: AgentId, sessionId: SessionId, size: Int) = lock.withLock {
        val file = file(agentId, sessionId)
        require(size >= 0)
        val saved = readEntries(file, sessionId)
        if (saved.size > size) writeAtomically(file, encode(saved.take(size)))
    }

    private fun load(id: String): Agent {
        return Agent(AgentId(id), files.doc(AgentId(id), AgentDoc.Identity).readTextOrNull()?.let(::identityName) ?: id, files.agentConfig(AgentId(id)))
    }

    private fun writeTemplate(id: String) {
        writeAtomically(files.config(AgentId(id)), Templates.config)
        Templates.docs.forEach { (doc, text) -> writeAtomically(files.doc(AgentId(id), doc), text) }
    }

    private fun cloned(id: String) = listOf(
        files.config(AgentId(id)), files.doc(AgentId(id), AgentDoc.Identity), files.doc(AgentId(id), AgentDoc.Soul), files.skills(AgentId(id)), files.mcp(AgentId(id)), files.env(AgentId(id)),
    )

    private fun readEntries(file: Path, sessionId: SessionId): List<SessionEntry> {
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

    private fun createdAt(sessionId: SessionId): Long? {
        if (!NATIVE_SESSION_ID.matches(sessionId.value)) return null
        return runCatching {
            LocalDateTime.parse(sessionId.value.take(15), ID_FORMAT).toInstant(TimeZone.currentSystemDefault()).toEpochMilliseconds()
        }.getOrNull()
    }

    private fun sessionDirectory(agentId: AgentId): Path {
        validateOwnerId(agentId)
        return files.sessions(agentId).also { require(isInside(files.agents, it)) { "Session directory escapes storage" } }
    }

    private fun taken(sessionId: SessionId) = files.agents.children().any { Path(it, "sessions", "$sessionId.jsonl").exists() }

    private fun file(agentId: AgentId, sessionId: SessionId): Path {
        validateSessionId(sessionId)
        val directory = sessionDirectory(agentId)
        val path = Path(directory, "$sessionId.jsonl")
        require(isInside(directory, path)) { "Session path escapes owner directory" }
        // Presence, run and subscription registries use globally unique Session IDs.
        require(files.agents.children().none { owner ->
            owner.name != agentId.value && Path(owner, "sessions", "$sessionId.jsonl").exists()
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
