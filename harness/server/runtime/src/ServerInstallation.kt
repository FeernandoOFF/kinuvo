package kinuvo.harness.server

import kinuvo.server.plugin.HarnessPlugin
import kinuvo.server.plugin.PluginHosts
import kinuvo.server.plugin.checkPluginInstances
import kinuvo.server.plugin.readTextOrNull
import kinuvo.server.plugin.writeAtomically
import kinuvo.harness.core.plugin.*
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readBuffer
import kinuvo.harness.core.*
import kinuvo.harness.core.artifacts.Artifacts
import kinuvo.harness.core.routines.Routines
import kinuvo.harness.core.store.*
import kinuvo.harness.core.tools.WebFetchTool
import kinuvo.harness.core.voice.OpenAICompatibleVoice
import kinuvo.protocol.*
import kotlin.time.Clock
import kotlin.uuid.Uuid
import kotlinx.io.files.Path
import kotlinx.io.readByteArray
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

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
            val installedStore = store ?: FileStore(files, clock)
            val docs = FileAgentDocs(files)
            val providers = object : AbstractList<ModelProvider>() {
                private fun current() = (modelProviders + plugins.filterIsInstance<ModelProviderPlugin>().flatMap { it.modelProviders }).distinctBy { it.id }
                override val size get() = current().size
                override fun get(index: Int) = current()[index]
            }
            if (store == null) claimShadowedProviders(files, installedStore, providers)
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
                }, settings, secrets, progress, serverId, resources, providers, { catalog.list(it) }, { WebFetchTool(client, reader = it) }, tracing, voiceProviders, clock)
        } catch (e: Throwable) {
            try { resources.close() } catch (cleanup: Throwable) { e.addSuppressed(cleanup) }
            throw e
        }
    }
}
