package kinuvo.harness.mcp

import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import io.modelcontextprotocol.kotlin.sdk.shared.Transport
import kinuvo.harness.core.store.AgentFiles
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

private val random = SecureRandom()

internal actual fun secureRandomBytes(count: Int) = ByteArray(count).also(random::nextBytes)

internal actual fun defaultSignIns(files: AgentFiles): McpSignInStore = McpSignIns(File(files.home.toString(), "mcp-auth"))

internal actual val stdioSupported: Boolean = true

internal actual fun startStdio(config: McpServerConfig.Stdio): StdioProcess = JvmStdioProcess(config)

private class JvmStdioProcess(config: McpServerConfig.Stdio) : StdioProcess {
    private val process = ProcessBuilder(listOf(config.command) + config.args).apply { environment().putAll(config.env) }.start()
    private val stderr = ArrayDeque<String>()
    private val stderrReader = thread(isDaemon = true, name = "mcp-${config.name}-stderr") {
        runCatching {
            process.errorStream.bufferedReader().forEachLine { line ->
                synchronized(stderr) { stderr.addLast(line); if (stderr.size > 20) stderr.removeFirst() }
            }
        }
    }

    override val transport: Transport = StdioClientTransport(
        input = process.inputStream.asSource().buffered(),
        output = process.outputStream.asSink().buffered(),
    )

    override fun detail(): String? {
        process.waitFor(500, TimeUnit.MILLISECONDS) // a crashing server is usually a moment from exiting
        stderrReader.join(500)
        val lines = synchronized(stderr) { stderr.filter { it.isNotBlank() }.takeLast(5) }
        val exit = if (process.isAlive) null else "exit code ${process.exitValue()}"
        return listOfNotNull(lines.joinToString(" | ").ifEmpty { null }, exit).joinToString(", ").ifEmpty { null }
    }

    override fun destroy() { process.destroy() }

    override fun destroyNow() { process.destroyForcibly() }
}

internal actual fun onShutdown(block: () -> Unit): () -> Unit {
    val hook = Thread(block).also { Runtime.getRuntime().addShutdownHook(it) }
    return { runCatching { Runtime.getRuntime().removeShutdownHook(hook) } }
}
