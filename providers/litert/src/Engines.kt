package kinuvo.providers.litert

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import io.github.oshai.kotlinlogging.KotlinLogging
import kinuvo.models.ModelFiles
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

private val log = KotlinLogging.logger {}

/**
 * Keeps one model loaded at a time, running one request at a time, and unloads it after [idle] ms unused. Loading tries
 * the GPU first and falls back to the CPU.
 */
internal class Engines(private val files: ModelFiles, private val cache: File, private val native: NativeEngine, private val idle: Long = 5 * 60_000) {
    private val lock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loaded: Pair<LocalModel, Engine>? = null
    private var unloading: Job? = null

    suspend fun <T> use(model: LocalModel, block: suspend (Engine) -> T): T = lock.withLock {
        unloading?.cancel()
        try { block(engine(model)) }
        finally { unloading = scope.launch { delay(idle); lock.withLock { close() } } }
    }

    /** Unloads [model] unless it's running, e.g. before removing its files. */
    fun unload(model: LocalModel) {
        if (!lock.tryLock()) return
        try { if (loaded?.first == model) close() } finally { lock.unlock() }
    }

    suspend fun shutdown() {
        scope.cancel()
        lock.withLock { close() }
    }

    private fun close() {
        loaded?.let { (model, engine) -> engine.close(); files.release(model.download) }
        loaded = null
    }

    private suspend fun engine(model: LocalModel): Engine = withContext(Dispatchers.IO) {
        loaded?.takeIf { it.first == model }?.let { return@withContext it.second }
        close()
        native.load()
        files.acquire(model.download)
        try {
            val path = File(files.folder(model.download), model.file).path
            cache.mkdirs()
            fun start(backend: Backend) = Engine(EngineConfig(path, backend, maxNumTokens = model.context, cacheDir = cache.path)).also { it.initialize() }
            val engine = try { start(Backend.GPU()) } catch (e: Exception) {
                log.info { "GPU unavailable for ${model.id}, using the CPU: ${e.message}" }
                start(Backend.CPU())
            }
            loaded = model to engine
            engine
        } catch (e: Throwable) { files.release(model.download); throw e }
    }
}
