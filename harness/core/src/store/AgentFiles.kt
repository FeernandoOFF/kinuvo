package kinuvo.harness.core.store

import co.touchlab.stately.concurrency.Lock
import co.touchlab.stately.concurrency.withLock
import com.charleskorn.kaml.Yaml
import kinuvo.harness.core.AgentConfig
import kinuvo.harness.core.AgentDoc
import kinuvo.protocol.ServiceKind
import kinuvo.protocol.ServiceSettings
import kotlinx.io.files.Path

/** Where everything lives under the Kinuvo home folder. The one place that knows the on-disk layout. */
class AgentFiles(val home: Path) {
    val agents = Path(home, "agents")
    val sharedSkills = Path(home, "skills")
    val sharedMcp = Path(home, MCP_FILE)
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
    fun mcp(id: String) = Path(agent(id), MCP_FILE)
    fun env(id: String) = Path(agent(id), ENV_FILE)

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

fun AgentFiles.knowledgeConfig() = config().knowledge

fun AgentFiles.knowledgeConventions(provider: String): String = conventions(provider).readTextOrNull().orEmpty()

/** The Knowledge folder at [path] (the configured one by default); [userHome] expands a leading `~/`. */
fun AgentFiles.knowledgeRoot(userHome: Path?, path: String? = knowledgeConfig().path): Path = path?.let { p ->
    if (p.startsWith("~/") && userHome != null) Path(userHome, p.drop(2)) else Path(p)
} ?: defaultKnowledge

fun AgentFiles.tasksConfig() = config().tasks

fun AgentFiles.saveTasksConfig(tasks: kinuvo.harness.core.tasks.TasksConfig) = updateConfig { it.copy(tasks = tasks) }

fun AgentFiles.serviceConfig(kind: ServiceKind): ServiceSettings = config().service(kind)

fun AgentFiles.saveServiceConfig(kind: ServiceKind, settings: ServiceSettings) = updateConfig { it.service(kind, settings) }

fun AgentFiles.saveKnowledgeConfig(knowledge: kinuvo.harness.core.knowledge.KnowledgeConfig) = updateConfig { it.copy(knowledge = knowledge) }

private val agentConfigLock = Lock()

fun AgentFiles.agentConfig(id: String): AgentConfig = Yaml.default.decodeFromString(AgentConfig.serializer(), config(id).readText())

/** Read-modify-write of `agent.yaml`, one at a time, so the config and its rules can't overwrite each other. */
fun AgentFiles.updateAgentConfig(id: String, transform: (AgentConfig) -> AgentConfig) = agentConfigLock.withLock {
    val old = agentConfig(id)
    transform(old).also { if (it != old) writeAtomically(config(id), Yaml.default.encodeToString(AgentConfig.serializer(), it) + "\n") }
}
