package kinuvo.harness.server

import kinuvo.harness.testing.*

import kinuvo.harness.core.*
import kinuvo.harness.core.artifacts.*
import kinuvo.harness.core.knowledge.*
import kinuvo.harness.core.routines.*
import kinuvo.harness.core.skills.*
import kinuvo.harness.core.store.InMemoryStore
import kinuvo.harness.core.store.Store
import kinuvo.harness.core.tasks.*
import kinuvo.harness.core.tools.Tool
import kinuvo.harness.core.tools.WebSearch
import kinuvo.protocol.*
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

internal class RuntimeFixture(plugins: List<HarnessPlugin> = emptyList(), providers: List<ModelProvider> = emptyList(), closeNetwork: () -> Unit = {}) {
    val agent = Agent("test", "Test", AgentConfig(provider = "test", model = "tools", tools = emptyList()))
    var members = listOf(agent)
    private val rooms = mutableMapOf<String, Room>()
    private val roomSessions = mutableMapOf<String, RoomSession>()
    val transcripts = InMemoryStore()
    val store = object : Store by transcripts {
        override suspend fun agents() = members
        override suspend fun rooms() = rooms.values.toList()
        override suspend fun saveRoom(room: Room) { rooms[room.id] = room }
        override suspend fun roomSessions(roomId: String) = roomSessions.values.filter { it.roomId == roomId }
        override suspend fun saveRoomSession(session: RoomSession) { roomSessions[session.id] = session }
        override suspend fun session(agent: Agent, sessionId: String) = transcripts.session(agent, sessionId).copy(settings = transcripts.entries(agent, sessionId).filterIsInstance<SessionEntry.Settings>().lastOrNull()?.settings ?: SessionSettings())
    }
    val documents = mutableMapOf<AgentDoc, String>()
    val agentDocuments = mutableMapOf<Pair<String, AgentDoc>, String>()
    val docs = object : AgentDocs {
        override suspend fun read(agentId: String, doc: AgentDoc) = agentDocuments[agentId to doc] ?: documents[doc].orEmpty()
        override suspend fun write(agentId: String, doc: AgentDoc, text: String) { documents[doc] = text }
        override suspend fun update(agentId: String, doc: AgentDoc, transform: (String) -> String): String = transform(read(agentId, doc)).also { write(agentId, doc, it) }
    }
    val skills = object : SkillRepository {
        override suspend fun all(agent: Agent) = emptyList<Skill>()
        override suspend fun content(skill: Skill): String = error("No Skills")
        override suspend fun resources(skill: Skill, limit: Int) = emptyList<String>()
        override suspend fun readFile(skill: Skill, path: String): String = error("No Skills")
        override suspend fun save(agent: Agent, scope: ConfigScope, name: String, content: String, files: Map<String, String>) = error("No Skills")
        override suspend fun exists(agent: Agent, scope: ConfigScope, name: String) = false
        override suspend fun delete(agent: Agent, scope: ConfigScope, name: String) = false
    }
    var rules = TrustRules()
    val trust = object : TrustRulesRepository {
        override suspend fun get(agentId: String) = rules
        override suspend fun update(agentId: String, transform: (TrustRules) -> TrustRules) { rules = transform(rules) }
    }
    var reflectionConfig = kinuvo.harness.core.store.ReflectionConfig()
    var execOptions = emptySet<String>()
    var notifier: Notifier? = null
    val settings = object : ServerSettings {
        override var timeZone: TimeZone = TimeZone.UTC
        override val reflectionModel get() = reflectionConfig
        override val reflectionEnabled = true
        override val reflectionPrompt = DEFAULT_REFLECTION_PROMPT
        override val integrations get() = mapOf(Integration.Knowledge to setOf("markdown"), Integration.Tasks to setOf("local"), Integration.Exec to execOptions)
        override fun tasksSetup() = TasksSetup(TasksSettings(), listOf("local"))
        override suspend fun saveTasksSettings(s: TasksSettings): TasksSetup = error("Not used")
        override fun knowledgeSetup() = KnowledgeSetup(KnowledgeSettings(), "", emptyList())
        override fun saveKnowledgeSettings(s: KnowledgeSettings): KnowledgeSetup = error("Not used")
        override suspend fun testKnowledge(s: KnowledgeSettings): KnowledgeTestResult = error("Not used")
        override fun serviceSetup(kind: ServiceKind) = ServiceSetup(kind, ServiceSettings(), emptyList())
        override fun saveService(kind: ServiceKind, s: ServiceSettings, secretUpdates: Map<String, SecretUpdate>): ServiceSetup = error("Not used")
        override suspend fun testService(p: TestServiceParams): ServiceTestResult = error("Not used")
        override fun webSearch(): WebSearch? = null
        override fun notifier(): Notifier? = this@RuntimeFixture.notifier
    }
    private var savedRoutines = emptyList<Routine>()
    val routines = Routines(object : RoutineRepository {
        override suspend fun get(agentId: String) = savedRoutines
        override suspend fun update(agentId: String, transform: (List<Routine>) -> List<Routine>) = transform(savedRoutines).also { savedRoutines = it }
    }, docs, defaultZone = { settings.timeZone }, clock = { 1000L })
    val artifactData = mutableMapOf<String, ArtifactData>()
    val artifacts = Artifacts(object : ArtifactRepository {
        override suspend fun list() = artifactData.values.map { it.info }
        override suspend fun get(id: String) = artifactData[id]
        override suspend fun save(info: ArtifactInfo, content: String) { artifactData[info.id] = ArtifactData(info, content) }
        override suspend fun delete(id: String) = artifactData.remove(id) != null
    }, clock = { 1000L })
    val knowledge = Knowledge({ error("No Knowledge source") }, object : KnowledgeChangeLog {
        override suspend fun add(change: KnowledgeChange) = error("Not used")
        override suspend fun list(limit: Int) = emptyList<KnowledgeChange>()
        override suspend fun get(id: String): KnowledgeChange? = null
        override suspend fun markUndone(id: String) = error("Not used")
    })
    val tasks = Tasks(emptyList(), object : BoardRepository {
        override suspend fun get() = emptyMap<String, TaskState>()
        override suspend fun update(transform: (Map<String, TaskState>) -> Map<String, TaskState>) = error("Not used")
    }, { TasksConfig() }, { setOf(agent.id) }, { LocalDate(2026, 10, 12) })
    var progress = emptyMap<String, Int>()
    var catalogReads = 0
    val resources = ServerResources(plugins, closeNetwork)
    var fetch: (WebSearch?) -> Tool = { error("Web fetch must be injected") }
    val dependencies = RuntimeDependencies(store, docs, skills, trust, routines, artifacts, knowledge, tasks,
        object : Attachments {
            override fun upload(params: UploadAttachmentParams): Attachment = error("Not used")
            override fun get(id: String): Attachment = error("Not used")
            override fun download(id: String): AttachmentData = error("Not used")
            override fun bytes(attachment: Attachment): ByteArray = error("Not used")
        }, settings, Secrets(), object : ReflectionProgress {
            override fun get() = progress
            override fun save(progress: Map<String, Int>) { this@RuntimeFixture.progress = progress }
        }, "in-memory-server", resources, providers, {
            catalogReads++
            ModelsCatalog(providers.map { ProviderModels(it.id, it.name, it.models(), false) })
        }, { fetch(it) })
    fun runtime(model: AgentModel? = null, features: Set<ServerFeature> = BUILT_IN_FEATURES) =
        HarnessRuntime(dependencies, modelFactory = model?.let { { _: AgentConfig -> it } }, builtInFeatures = features)
}
