package kinuvo.harness.server

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readBuffer
import kinuvo.harness.core.*
import kinuvo.harness.core.artifacts.Artifacts
import kinuvo.harness.core.knowledge.Knowledge
import kinuvo.harness.core.routines.Routines
import kinuvo.harness.core.skills.SkillRepository
import kinuvo.harness.core.store.*
import kinuvo.harness.core.tasks.Tasks
import kinuvo.harness.core.tools.Tool
import kinuvo.harness.core.tools.WebFetchTool
import kinuvo.harness.core.tools.WebSearch
import kinuvo.harness.core.voice.OpenAICompatibleVoice
import kinuvo.harness.core.voice.VoiceProviders
import kinuvo.protocol.*
import kotlin.time.Clock
import kotlin.uuid.Uuid
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlinx.io.readByteArray
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

interface ReflectionProgress {
    fun get(): Map<String, Int>
    fun save(progress: Map<String, Int>)
}

interface Attachments {
    fun upload(params: UploadAttachmentParams): Attachment
    fun get(id: String): Attachment
    fun download(id: String): AttachmentData
    fun bytes(attachment: Attachment): ByteArray
}

/** Owns installed plugins and network resources; shutdown is complete even when a closer fails or is cancelled. */
class ServerResources(val plugins: List<HarnessPlugin>, private val closeNetwork: () -> Unit = {}) {
    private val lock = Mutex()
    private var closed = false

    suspend fun release(agentId: String) = lock.withLock {
        if (closed) return@withLock
        var failure: Throwable? = null
        plugins.distinct().forEach { plugin ->
            try { plugin.release(agentId) } catch (e: Throwable) { if (failure == null) failure = e else failure.addSuppressed(e) }
        }
        failure?.let { throw it }
    }

    suspend fun close() = withContext(NonCancellable) { lock.withLock {
        if (closed) return@withLock
        closed = true
        plugins.distinct().forEach { runCatching { it.close() } }
        closeNetwork()
    } }
}

/** Execution accepts domain adapters, with no installation paths or concrete file construction. */
class RuntimeDependencies(
    val store: Store,
    val docs: AgentDocs,
    val skills: SkillRepository,
    val trustRules: TrustRulesRepository,
    val routines: Routines,
    val artifacts: Artifacts,
    val knowledge: Knowledge,
    val tasks: Tasks,
    val attachments: Attachments,
    val settings: ServerSettings,
    val secrets: Secrets,
    val reflectionProgress: ReflectionProgress,
    val serverId: String,
    val resources: ServerResources,
    val modelProviders: List<ModelProvider>,
    val modelCatalog: suspend (Boolean) -> ModelsCatalog,
    val webFetch: (WebSearch?) -> Tool,
    val tracing: AgentTracing? = null,
    val voice: VoiceProviders = VoiceProviders(),
) {
    val plugins get() = resources.plugins
    val mcp = plugins.firstNotNullOfOrNull { it.mcp } ?: McpManagement.Unavailable
    val execCheck = plugins.firstNotNullOfOrNull { it.execCheck }
    val usage: StateFlow<Usage?> = modelProviders.firstNotNullOfOrNull { it.usage } ?: MutableStateFlow(null)
}

/** Both hosts install the same disk adapters and own the resulting resources through the runtime. */
object ServerInstallation {
    suspend fun assemble(
        files: AgentFiles,
        store: Store? = null,
        plugins: List<HarnessPlugin> = emptyList(),
        modelProviders: List<ModelProvider> = emptyList(),
        secrets: Secrets = Secrets(),
        userHome: Path = files.home,
        clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    ): RuntimeDependencies {
        var http: HttpClient? = null
        var hosts: PluginHosts? = null
        var tracing: AgentTracing? = null
        var voice: OpenAICompatibleVoice? = null
        val resources = ServerResources(plugins) { try { tracing?.close() } finally { voice?.close(); hosts?.close(); http?.close() } }
        try {
            plugins.checkPluginInstances()
            tracing = if (files.config().tracing) localTracing(files) else null
            val installedStore = store ?: FileStore(files)
            val docs = FileAgentDocs(files)
            val providers = (modelProviders + plugins.flatMap { it.modelProviders }).associateBy { it.id }.values.toList()
            val client = HttpClient {
                followRedirects = true
                install(HttpTimeout) { requestTimeoutMillis = 30_000 }
            }.also { http = it }
            val pluginHosts = PluginHosts(client) { instance, value ->
                secrets.expand(value, files.loadEnv(), "Kinuvo: plugin ${instance.pluginId}/${instance.instanceId}")
            }.also { hosts = it }
            val voiceProviders = OpenAICompatibleVoice(client) { value ->
                secrets.expand(value, files.loadEnv(), "Kinuvo: voice provider")
            }.also { voice = it }.providers(files.config().voice)
            val settings = FileServerSettings(files, userHome, installedStore, plugins, secrets, pluginHosts, clock)
            val catalog = ModelCatalog(Path(files.home, "cache", "models.json"), providers, {
                val response = client.get("https://models.dev/api.json")
                check(response.status.value == 200) { "Models.dev request failed" }
                val bytes = response.bodyAsChannel().readBuffer(10 * 1024 * 1024L + 1).readByteArray()
                require(bytes.size <= 10 * 1024 * 1024) { "Model catalog is too large" }
                Json.parseToJsonElement(bytes.decodeToString()).jsonObject
            }, clock)
            val attachmentFiles = FileAttachments(Path(files.home, "attachments"))
            val progress = object : ReflectionProgress {
                private val serializer = MapSerializer(String.serializer(), Int.serializer())
                override fun get() = files.reflections.readTextOrNull()?.let { runCatching { Json.decodeFromString(serializer, it) }.getOrNull() }.orEmpty()
                override fun save(progress: Map<String, Int>) = writeAtomically(files.reflections, Json.encodeToString(serializer, progress))
            }
            val serverId = Path(files.home, "server-id").let { file ->
                file.readTextOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: Uuid.random().toString().also { writeAtomically(file, it) }
            }
            return RuntimeDependencies(installedStore, docs, FileSkillRepository(files, userHome), FileTrustRules(files),
                Routines(FileRoutineRepository(files), docs, defaultZone = { settings.timeZone }, clock = clock),
                Artifacts(FileArtifacts(files.artifacts), clock), settings.knowledge, settings.tasks,
                object : Attachments {
                    override fun upload(params: UploadAttachmentParams) = attachmentFiles.upload(params)
                    override fun get(id: String) = attachmentFiles.get(id)
                    override fun download(id: String) = attachmentFiles.download(id)
                    override fun bytes(attachment: Attachment) = attachmentFiles.bytes(attachment)
                }, settings, secrets, progress, serverId, resources, providers, { catalog.list(it) }, { WebFetchTool(client, reader = it) }, tracing, voiceProviders)
        } catch (e: Throwable) {
            try { resources.close() } catch (cleanup: Throwable) { e.addSuppressed(cleanup) }
            throw e
        }
    }
}
