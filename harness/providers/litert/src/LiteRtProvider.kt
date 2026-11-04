package kinuvo.harness.litert

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.llm.LLMProvider
import kinuvo.harness.core.*
import kinuvo.harness.core.tools.ToolNames
import kinuvo.harness.core.tools.smallModelTools
import kinuvo.models.ModelFiles
import kinuvo.models.gigabytes
import kinuvo.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.io.File

internal object LiteRt : LLMProvider("local", "On this device")

/** Open models the Server downloads into [home] and runs on its own machine with LiteRT-LM. */
class LiteRtProvider(home: File) : ModelProvider, HarnessPlugin {
    override val definition = PluginDefinition("kinuvo.litert", "On this device")
    override val id = "local"
    override val name = "On this device"
    override val modelProviders get() = listOf(this)
    private val files = ModelFiles(File(home, "local-models"))
    private val engines = Engines(files, File(home, "cache/litert"))
    private val executor = koogExecutor(LiteRtLLMClient(engines))
    private val memory = deviceMemory()
    private val unsupported = unsupportedReason()

    private fun reason(model: LocalModel) = unsupported
        ?: memory?.takeIf { it < model.memory * 9 / 10 }?.let { "Needs ${gigabytes(model.memory)} of RAM; this machine has ${gigabytes(it)}" }

    override suspend fun models() = localModels.filter { reason(it) == null }.map { it.info() }

    override suspend fun availability(model: String): CapabilityState {
        val local = localModels.firstOrNull { it.id == model } ?: return CapabilityState.unsupported("No on-device model '$model'")
        reason(local)?.let { return CapabilityState.unsupported(it) }
        return if (files.installed(local.download)) CapabilityState()
        else CapabilityState.unavailable("Download ${local.name} in Settings → Model providers", "not-downloaded")
    }

    override fun usableTools(model: String, tools: List<ToolDescriptor>) = usableTools(model, tools, emptyMap())

    /** Screen control, Scripts and the tool catalog take more steps and context than these models handle well. */
    override fun usableTools(model: String, tools: List<ToolDescriptor>, identities: Map<String, ToolIdentity>) =
        smallModelTools(tools, localModels.firstOrNull { it.id == model }?.tools ?: 4, { identities[it.name]?.localId ?: toolLocalName(it.name) }) { tool ->
            (identities[tool.name]?.localId ?: toolLocalName(tool.name)).let { it !in SCREENS_AND_SCRIPTS && !it.startsWith("browser") }
        }

    override fun model(config: AgentConfig): AgentModel = model(config, null)

    override fun model(config: AgentConfig, info: ModelInfo?): AgentModel {
        val local = requireNotNull(localModels.firstOrNull { it.id == config.model }) { "No on-device model '${config.model}'" }
        return AgentModel(executor, (info ?: local.info()).llModel(LiteRt))
    }

    override val downloads = object : ModelDownloads {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private var job: Job? = null
        private val progress = MutableStateFlow(ProviderDownloads())
        override val changes = MutableStateFlow(state())

        override fun state() = progress.value.copy(models = localModels.map {
            DownloadableModel(it.id, it.name, it.download.bytes, it.memory, files.installed(it.download), reason(it) == null, reason(it), it.license, it.source)
        })

        private fun publish(change: (ProviderDownloads) -> ProviderDownloads) { progress.update(change); changes.value = state() }

        @OptIn(DelicateCoroutinesApi::class)
        @Synchronized override fun download(model: String) {
            check(job?.isCompleted != false) { "A download is already active" }
            val local = requireNotNull(localModels.firstOrNull { it.id == model }) { "No on-device model '$model'" }
            reason(local)?.let { error(it) }
            publish { ProviderDownloads(downloading = model) }
            job = scope.launch(start = CoroutineStart.ATOMIC) {
                var reported = 0L
                try {
                    files.download(local.download) { bytes ->
                        if (bytes - reported >= REPORT || bytes == local.download.bytes) { reported = bytes; publish { it.copy(downloaded = bytes) } }
                    }
                    publish { ProviderDownloads() }
                } catch (e: CancellationException) {
                    publish { ProviderDownloads() }
                    throw e
                } catch (e: Exception) {
                    publish { ProviderDownloads(error = e.message ?: "Download failed") }
                }
            }
        }

        override fun cancel() { job?.cancel() }

        override suspend fun remove(model: String) {
            val local = requireNotNull(localModels.firstOrNull { it.id == model }) { "No on-device model '$model'" }
            check(progress.value.downloading != model) { "Wait for the download to finish" }
            engines.unload(local)
            files.remove(local.download)
            publish { it.copy(error = null) }
        }
    }

    override suspend fun close() { engines.shutdown(); executor.close() }

    companion object {
        private const val REPORT = 8L * 1024 * 1024
        private val SCREENS_AND_SCRIPTS = setOf(ToolNames.COMPUTER, ToolNames.COMPUTER_ACT, ToolNames.RUN_CODE, ToolNames.FIND_TOOLS, ToolNames.DESCRIBE_TOOL, ToolNames.CALL_TOOL)
    }
}

private fun LocalModel.info() = ModelInfo(id, name, reasoning = emptyList(), input = listOf("text"), tools = true, context = context, location = ExecutionLocation.ServerHost)
