package kinuvo.harness.server

import kotlinx.coroutines.*
import java.io.File

class HarnessServer(val harness: Harness, val auth: DeviceAuth = DeviceAuth(File(harness.files.home.toString(), "server-devices.json")), private val name: String = machineName(), val network: NetworkSecurity = NetworkSecurity(), security: ConnectionSecurity = ConnectionSecurity()) {
    val runtime = ServerRuntime(harness, auth, name, security = security, prepareAttachment = ::prepareAttachment)
    private val listener = NetworkListener(runtime, auth, network)
    private var started = false
    private var advertised: LocalServer? = null

    suspend fun start(host: String = "0.0.0.0", port: Int = DEFAULT_PORT, schedule: Boolean = true): Int = start(host, listOf(port), schedule)
    suspend fun start(host: String, ports: List<Int>, schedule: Boolean = true): Int {
        check(!started) { "Server already started" }
        started = true
        try {
            runtime.start(schedule)
            return listener.start(host, ports)
        } catch (e: Throwable) {
            withContext(NonCancellable) {
                try { listener.stop() } catch (cleanup: Throwable) { e.addSuppressed(cleanup) }
                try { runtime.close() } catch (cleanup: Throwable) { e.addSuppressed(cleanup) }
            }
            throw e
        }
    }
    fun advertise() {
        val host = listener.boundHost.takeUnless { it == "0.0.0.0" || it == "::" } ?: "127.0.0.1"
        val local = LocalServer(host, listener.boundPort, auth.sessionToken(), ProcessHandle.current().pid())
        LocalServer.write(harness.files.home, local)
        advertised = local
    }
    companion object {
        const val DEFAULT_PORT = 8787
        val EMBEDDED_PORTS = (DEFAULT_PORT..DEFAULT_PORT + 4).toList()
    }
    fun stop() = runBlocking {
        advertised?.let { if (LocalServer.read(harness.files.home) == it) LocalServer.delete(harness.files.home) }
        listener.stop()
        runtime.close()
    }
}

/** What clients call this Server until they give it an alias. */
private fun machineName() = runCatching { java.net.InetAddress.getLocalHost().hostName.substringBefore('.') }.getOrNull()?.takeIf { it.isNotBlank() && it != "localhost" } ?: "kinuvo"
