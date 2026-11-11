package kinuvo.server.store

import kinuvo.server.plugin.exists
import kinuvo.server.plugin.readText
import kinuvo.server.plugin.readTextOrNull
import kinuvo.server.plugin.writeAtomically
import co.touchlab.stately.concurrency.Lock
import co.touchlab.stately.concurrency.withLock
import com.charleskorn.kaml.Yaml
import kinuvo.harness.AgentConfig
import kinuvo.harness.AgentDoc
import kinuvo.protocol.ServiceKind
import kinuvo.protocol.ServiceSettings
import kotlinx.io.files.Path
import kinuvo.server.knowledge.KnowledgeConfig
import kinuvo.server.parseDotEnv
import kinuvo.server.plugin.CustomProviderStore
import kinuvo.server.plugin.ModelProviderConfig
import kinuvo.server.plugin.PluginStorage
import kinuvo.server.tasks.TasksConfig

/** Where everything lives under the Kinuvo home folder. The one place that knows the on-disk layout. */
class AgentFiles(override val home: Path) : PluginStorage, CustomProviderStore {
    val agents = Path(home, "agents")
    val sharedSkills = Path(home, "skills")
    override val sharedMcp = Path(home, MCP_FILE)
    val sharedEnv = Path(home, ENV_FILE)
    val serverConfig = Path(home, "config.yaml")
    val knowledgeChanges = Path(home, "knowledge-changes.jsonl")
    val defaultKnowledge = Path(home, "knowledge")
    val reflections = Path(home, "reflections.json")
    val board = Path(home, "board.json")
    val localTasks = Path(home, "tasks.json")
    val artifacts = Path(home, "artifacts")
    val traces = Path(home, "traces")

    /** How the user organizes notes in a knowledge [provider], in their words, for Agents to follow. */
    fun conventions(provider: String) = Path(home, "conventions", "$provider.md")

    fun agent(id: String) = Path(agents, id)
    fun config(id: String) = Path(agent(id), CONFIG_FILE)
    fun doc(id: String, doc: AgentDoc) = Path(agent(id), doc.fileName)
    fun sessions(id: String) = Path(agent(id), "sessions")
    fun routines(id: String) = Path(agent(id), "routines.json")
    fun skills(id: String) = Path(agent(id), "skills")
    override fun mcp(id: String) = Path(agent(id), MCP_FILE)
    fun env(id: String) = Path(agent(id), ENV_FILE)

    override fun agentExists(id: String) = config(id).exists()

    /** Variables for `${VAR}` expansion: `<home>/.env`, then the Agent's `.env`, then the process environment (strongest). */
    override fun loadEnv(agentId: String?, process: Map<String, String>): Map<String, String> =
        dotEnv(sharedEnv) + agentId?.let { dotEnv(env(it)) }.orEmpty() + process

    override fun knowledgeOptions() = knowledgeConfig().options

    override fun customProviders() = config().providers

    override fun saveCustomProvider(id: String, options: ModelProviderConfig) = updateConfig { it.copy(providers = it.providers + (id to options)) }

    companion object {
        const val CONFIG_FILE = "agent.yaml"
        const val MCP_FILE = "mcp.json"
        const val ENV_FILE = ".env"

        val AgentDoc.fileName get() = when (this) {
            AgentDoc.Identity -> "IDENTITY.md"
            AgentDoc.Soul -> "SOUL.md"
            AgentDoc.Memory -> "MEMORY.md"
            AgentDoc.UserNotes -> "USER.md"
            AgentDoc.Heartbeat -> "HEARTBEAT.md"
            AgentDoc.ReflectionFeedback -> "reflection-feedback.json"
        }
    }
}

private fun dotEnv(file: Path) = file.readTextOrNull()?.let(::parseDotEnv).orEmpty()

fun AgentFiles.knowledgeConfig() = config().knowledge

fun AgentFiles.knowledgeConventions(provider: String): String = conventions(provider).readTextOrNull().orEmpty()

/** The Knowledge folder at [path] (the configured one by default); [userHome] expands a leading `~/`. */
fun AgentFiles.knowledgeRoot(userHome: Path?, path: String? = knowledgeConfig().path): Path = path?.let { p ->
    if (p.startsWith("~/") && userHome != null) Path(userHome, p.drop(2)) else Path(p)
} ?: defaultKnowledge

fun AgentFiles.tasksConfig() = config().tasks

fun AgentFiles.saveTasksConfig(tasks: TasksConfig) = updateConfig { it.copy(tasks = tasks) }

fun AgentFiles.serviceConfig(kind: ServiceKind): ServiceSettings = config().service(kind)

fun AgentFiles.saveServiceConfig(kind: ServiceKind, settings: ServiceSettings) = updateConfig { it.service(kind, settings) }

fun AgentFiles.saveKnowledgeConfig(knowledge: KnowledgeConfig) = updateConfig { it.copy(knowledge = knowledge) }

private val agentConfigLock = Lock()

fun AgentFiles.agentConfig(id: String): AgentConfig = Yaml.default.decodeFromString(AgentConfig.serializer(), config(id).readText())

/** Read-modify-write of `agent.yaml`, one at a time, so the config and its rules can't overwrite each other. */
fun AgentFiles.updateAgentConfig(id: String, transform: (AgentConfig) -> AgentConfig) = agentConfigLock.withLock {
    val old = agentConfig(id)
    transform(old).also { if (it != old) writeAtomically(config(id), Yaml.default.encodeToString(AgentConfig.serializer(), it) + "\n") }
}
