package kinuvo.harness.core.store

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
    val knowledgeConfig = Path(home, "knowledge.yaml")
    val knowledgeChanges = Path(home, "knowledge-changes.jsonl")
    val defaultKnowledge = Path(home, "knowledge")
    val reflections = Path(home, "reflections.json")
    val tasksConfig = Path(home, "tasks.yaml")
    val board = Path(home, "board.json")
    val localTasks = Path(home, "tasks.json")
    val artifacts = Path(home, "artifacts")

    /** How the user organizes notes in a knowledge [provider], in their words, for Agents to follow. */
    fun conventions(provider: String) = Path(home, "conventions", "$provider.md")

    fun agent(id: String) = Path(agents, id)
    fun config(id: String) = Path(agent(id), CONFIG_FILE)
    fun doc(id: String, doc: AgentDoc) = Path(agent(id), doc.fileName)
    fun sessions(id: String) = Path(agent(id), "sessions")
    fun routines(id: String) = Path(agent(id), "routines.json")
    fun trustRules(id: String) = Path(agent(id), "approvals.yaml")
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
        }
    }
}

/** `knowledge.yaml`, or the defaults when it's missing. */
fun AgentFiles.knowledgeConfig(): kinuvo.harness.core.knowledge.KnowledgeConfig =
    knowledgeConfig.readTextOrNull()?.takeIf { it.isNotBlank() }
        ?.let { com.charleskorn.kaml.Yaml.default.decodeFromString(kinuvo.harness.core.knowledge.KnowledgeConfig.serializer(), it) }
        ?: kinuvo.harness.core.knowledge.KnowledgeConfig()

fun AgentFiles.knowledgeConventions(provider: String): String = conventions(provider).readTextOrNull().orEmpty()

/** The Knowledge folder at [path] (knowledge.yaml's by default); [userHome] expands a leading `~/`. */
fun AgentFiles.knowledgeRoot(userHome: Path?, path: String? = knowledgeConfig().path): Path = path?.let { p ->
    if (p.startsWith("~/") && userHome != null) Path(userHome, p.drop(2)) else Path(p)
} ?: defaultKnowledge

/** `tasks.yaml`, or the defaults when it's missing. */
fun AgentFiles.tasksConfig(): kinuvo.harness.core.tasks.TasksConfig =
    tasksConfig.readTextOrNull()?.takeIf { it.isNotBlank() }
        ?.let { com.charleskorn.kaml.Yaml.default.decodeFromString(kinuvo.harness.core.tasks.TasksConfig.serializer(), it) }
        ?: kinuvo.harness.core.tasks.TasksConfig()

fun AgentFiles.saveTasksConfig(config: kinuvo.harness.core.tasks.TasksConfig) =
    writeAtomically(tasksConfig, com.charleskorn.kaml.Yaml.default.encodeToString(kinuvo.harness.core.tasks.TasksConfig.serializer(), config) + "\n")

/** `search.yaml` or `notifications.yaml`. */
fun AgentFiles.service(kind: ServiceKind) = Path(home, if (kind == ServiceKind.Search) "search.yaml" else "notifications.yaml")

fun AgentFiles.serviceConfig(kind: ServiceKind): ServiceSettings =
    service(kind).readTextOrNull()?.takeIf { it.isNotBlank() }?.let { com.charleskorn.kaml.Yaml.default.decodeFromString(ServiceSettings.serializer(), it) } ?: ServiceSettings()

/** Owner-only, since options can hold API keys. */
fun AgentFiles.saveServiceConfig(kind: ServiceKind, config: ServiceSettings) =
    writeAtomically(service(kind), com.charleskorn.kaml.Yaml.default.encodeToString(ServiceSettings.serializer(), config) + "\n", private = true)

/** Owner-only, since provider options can hold API keys. */
fun AgentFiles.saveKnowledgeConfig(config: kinuvo.harness.core.knowledge.KnowledgeConfig) =
    writeAtomically(knowledgeConfig, com.charleskorn.kaml.Yaml.default.encodeToString(kinuvo.harness.core.knowledge.KnowledgeConfig.serializer(), config) + "\n", private = true)
