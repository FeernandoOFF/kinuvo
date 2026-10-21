package kinuvo.harness.core.store

import co.touchlab.stately.concurrency.Lock
import co.touchlab.stately.concurrency.withLock
import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import kinuvo.harness.core.knowledge.KnowledgeConfig
import kinuvo.harness.core.tasks.TasksConfig
import kinuvo.protocol.ServiceKind
import kinuvo.protocol.ServiceSettings
import kinuvo.protocol.TrustRules
import kotlinx.serialization.Serializable

/** `config.yaml` in the home folder: everything set up for the whole Server. [rules] are its Trust and Deny rules, for every Agent. */
@Serializable
data class ServerConfig(
    val timezone: String? = null,
    val tracing: Boolean = true,
    val knowledge: KnowledgeConfig = KnowledgeConfig(),
    val reflection: ReflectionConfig = ReflectionConfig(),
    val tasks: TasksConfig = TasksConfig(),
    val search: ServiceSettings = ServiceSettings(),
    val notifications: ServiceSettings = ServiceSettings(),
    val rules: TrustRules = TrustRules(),
) {
    fun service(kind: ServiceKind) = if (kind == ServiceKind.Search) search else notifications
    fun service(kind: ServiceKind, settings: ServiceSettings) = if (kind == ServiceKind.Search) copy(search = settings) else copy(notifications = settings)
}

@Serializable
data class ReflectionConfig(val provider: String? = null, val model: String? = null)

private val yaml = Yaml(configuration = YamlConfiguration(encodeDefaults = false))
private val configLock = Lock()

/** `config.yaml`, or the defaults when it's missing. */
fun AgentFiles.config(): ServerConfig =
    serverConfig.readTextOrNull()?.takeIf { it.isNotBlank() }?.let { yaml.decodeFromString(ServerConfig.serializer(), it) } ?: ServerConfig()

/** Owner-only, since options can hold API keys. */
fun AgentFiles.updateConfig(transform: (ServerConfig) -> ServerConfig) = configLock.withLock {
    val old = config()
    val new = transform(old)
    if (new != old) writeAtomically(serverConfig, yaml.encodeToString(ServerConfig.serializer(), new), private = true)
}
