package kinuvo.harness.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class ProcessOutput(val code: Int, val out: String, val err: String) {
    val ok get() = code == 0
    /** stdout, or an error with what the command printed (never its stdout, which may hold a secret). */
    fun value(command: String): String = if (ok) out else error(err.trim().removePrefix("Error: ").ifEmpty { "$command failed ($code)" })
}

/** Runs [command] with [env] added to the Server's environment, writing [stdin] to it; IOException when it can't start. */
suspend fun runProcess(command: List<String>, env: Map<String, String> = emptyMap(), stdin: String? = null): ProcessOutput = withContext(Dispatchers.IO) {
    withTimeout(60_000) {
        coroutineScope {
            val process = ProcessBuilder(command).also { it.environment().putAll(env) }.start()
            try {
                process.outputStream.use { s -> stdin?.let { s.write(it.toByteArray()) } }
                val out = async { runInterruptible { process.inputStream.bufferedReader().use { it.readText() } } }
                val err = async { runInterruptible { process.errorStream.bufferedReader().use { it.readText() } } }
                val code = runInterruptible { process.waitFor() }
                ProcessOutput(code, out.await(), err.await())
            } finally {
                if (process.isAlive) process.destroyForcibly()
            }
        }
    }
}
