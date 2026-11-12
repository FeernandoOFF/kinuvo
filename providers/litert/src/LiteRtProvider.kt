package kinuvo.providers.litert

import kinuvo.harness.AgentConfig
import kinuvo.server.plugin.ModelDownloads
import kinuvo.harness.builtin.plugin.*
import kinuvo.server.plugin.ProviderAdmin
import kinuvo.server.plugin.ProviderPlugin
import ai.koog.prompt.llm.LLMProvider
import kinuvo.harness.builtin.*
import kinuvo.models.ModelFiles
import kinuvo.models.gigabytes
import kinuvo.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.io.File

internal object LiteRt : LLMProvider("local", "On this device")

/** Open models the Server downloads into [home] and runs on its own machine with LiteRT-LM. */
class LiteRtProvider(home: File) : ModelSource, ProviderAdmin, ProviderPlugin {
    override val definition = PluginDefinition("kinuvo.litert", "On this device")
    override val id = "local"
    override val name = "On this device"
    override val providerAdmins get() = listOf(this)
    private val files = ModelFiles(File(home, "local-models"))
    private val native = nativeEngine(home)
    private val engines = Engines(files, File(home, "cache/litert"), native)
    private val executor = koogExecutor(LiteRtLLMClient(engines))
    private val memory = deviceMemory()
    private val unsupported = unsupportedReason()

    private fun reason(model: LocalModel) = unsupported
        ?: memory?.takeIf { it < model.memory * 9 / 10 }?.let { "Needs ${gigabytes(model.memory)} of RAM; this machine has ${gigabytes(it)}" }

    override suspend fun models() = localModels.filter { reason(it) == null }.map { it.info() }

    override suspend fun availability(model: String): CapabilityState {
        val local = localModels.firstOrNull { it.id == model } ?: return CapabilityState.unsupported("No on-device model '$model'")
        reason(local)?.let { return CapabilityState.unsupported(it) }
        return if (native.installed && files.installed(local.download)) CapabilityState()
        else CapabilityState.unavailable("Download ${local.name} in Settings → Model providers", "not-downloaded")
    }

    override fun profile(model: String, base: ModelProfile) = localModels.firstOrNull { it.id == model }
        ?.takeIf { it.tier == base.tier }?.cap?.let { base.copy(tools = base.tools.copy(cap = it)) } ?: base

    override fun model(config: AgentConfig): AgentModel = model(config, null)

    override fun model(config: AgentConfig, info: ModelInfo?): AgentModel {
        val local = requireNotNull(localModels.firstOrNull { it.id == config.model }) { "No on-device model '${config.model}'" }
        return AgentModel(executor, (info ?: local.info()).llModel(LiteRt), instructionsInPrompt = false)
    }

    private val downloadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val downloads = object : ModelDownloads {
        private var job: Job? = null
        private val progress = MutableStateFlow(ProviderDownloads())
        override val changes = MutableStateFlow(state())

        override fun state() = progress.value.copy(models = localModels.map {
            DownloadableModel(it.id, it.name, it.download.bytes + native.bytes, it.memory, native.installed && files.installed(it.download), reason(it) == null, reason(it), it.license, it.source)
        })

        private fun publish(change: (ProviderDownloads) -> ProviderDownloads) { progress.update(change); changes.value = state() }

        @OptIn(DelicateCoroutinesApi::class)
        @Synchronized override fun download(model: String) {
            check(job?.isCompleted != false) { "A download is already active" }
            val local = requireNotNull(localModels.firstOrNull { it.id == model }) { "No on-device model '$model'" }
            reason(local)?.let { error(it) }
            publish { ProviderDownloads(downloading = model) }
            job = downloadScope.launch(start = CoroutineStart.ATOMIC) {
                var reported = 0L
                try {
                    native.ensure { bytes ->
                        if (bytes - reported >= REPORT || bytes == native.bytes) { reported = bytes; publish { it.copy(downloaded = bytes) } }
                    }
                    if (!files.installed(local.download)) files.download(local.download) { modelBytes ->
                        val bytes = native.bytes + modelBytes
                        if (bytes - reported >= REPORT || bytes == native.bytes + local.download.bytes) { reported = bytes; publish { it.copy(downloaded = bytes) } }
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

    override suspend fun close() {
        downloadScope.coroutineContext.job.cancelAndJoin()
        engines.shutdown()
        executor.close()
    }

    companion object {
        private const val REPORT = 8L * 1024 * 1024
    }
}

private fun LocalModel.info() = ModelInfo(id, name, reasoning = emptyList(), input = listOf("text"), tools = true, context = context, location = ExecutionLocation.ServerHost, tier = tier)
