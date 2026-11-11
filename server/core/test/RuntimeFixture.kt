package kinuvo.server

import kinuvo.harness.Agent
import kinuvo.harness.AgentConfig
import kinuvo.harness.AgentDoc
import kinuvo.harness.AgentDocs
import kinuvo.server.plugin.HarnessPlugin
import kinuvo.server.plugin.Notifier
import kinuvo.harness.builtin.plugin.*
import kinuvo.server.plugin.*
import kinuvo.harness.builtin.*
import kinuvo.server.artifacts.*
import kinuvo.harness.builtin.knowledge.*
import kinuvo.server.knowledge.*
import kinuvo.harness.builtin.routines.*
import kinuvo.server.routines.*
import kinuvo.server.skills.*
import kinuvo.server.store.InMemoryStore
import kinuvo.server.store.Store
import kinuvo.server.tasks.*
import kinuvo.server.plugin.Tool
import kinuvo.server.plugin.WebSearch
import kinuvo.server.plugin.VoiceProviders
import kinuvo.testing.harness.*
import kinuvo.protocol.*
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

internal class RuntimeFixture(plugins: List<HarnessPlugin> = emptyList(), providers: List<ModelProvider> = emptyList(), closeNetwork: () -> Unit = {}, voice: VoiceProviders = VoiceProviders()) {
    val agent = Agent("test", "Test", AgentConfig(provider = "test", model = "tools", tools = emptyList()))
    val transcripts = InMemoryStore()
    val store = object : Store by transcripts {
        override suspend fun agents() = listOf(agent)
        override suspend fun session(agentId: String, sessionId: String) = transcripts.session(agentId, sessionId).copy(settings = transcripts.entries(agentId, sessionId).filterIsInstance<SessionEntry.Settings>().lastOrNull()?.settings ?: SessionSettings())
    }
    val documents = mutableMapOf<AgentDoc, String>()
    val docs = object : AgentDocs {
        override suspend fun read(agentId: String, doc: AgentDoc) = documents[doc].orEmpty()
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
    var reflectionConfig = kinuvo.server.store.ReflectionConfig()
    var push = true
    var providerTest = ServiceTestResult(error = "Pick a provider first")
    val settings = object : ServerSettings {
        override var liveVoiceEnabled = false
        override var timeZone: TimeZone = TimeZone.UTC
        override val reflectionModel get() = reflectionConfig
        override val reflectionEnabled = true
        override val reflectionPrompt = DEFAULT_REFLECTION_PROMPT
        override val integrations = mapOf(Integration.Knowledge to setOf("markdown"), Integration.Tasks to setOf("local"))
        override fun tasksSetup() = TasksSetup(TasksSettings(), listOf(SettingsProvider("local")))
        override suspend fun saveTasksSettings(s: TasksSettings, secretUpdates: Map<String, SecretUpdate>): TasksSetup = error("Not used")
        override suspend fun taskProviderLists(ref: TaskProviderRef): List<TaskListInfo> = error("Not used")
        override fun knowledgeSetup() = KnowledgeSetup(KnowledgeSettings(), "", emptyList())
        override fun saveKnowledgeSettings(s: KnowledgeSettings): KnowledgeSetup = error("Not used")
        override suspend fun testKnowledge(s: KnowledgeSettings): KnowledgeTestResult = error("Not used")
        override fun serviceSetup(kind: ServiceKind) = ServiceSetup(kind, ServiceSettings(push = push), emptyList())
        override fun saveService(kind: ServiceKind, s: ServiceSettings, secretUpdates: Map<String, SecretUpdate>): ServiceSetup = error("Not used")
        override suspend fun testService(p: TestServiceParams) = providerTest
        override fun webSearch(): WebSearch? = null
        override fun notifier(): Notifier? = null
        override val pushNotifications get() = push
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
        }, { fetch(it) }, voice = voice)
    fun runtime(model: AgentModel? = null, features: Set<ServerFeature> = BUILT_IN_FEATURES) =
        HarnessRuntime(dependencies, modelFactory = model?.let { { _: AgentConfig -> it } }, builtInFeatures = features)
}
