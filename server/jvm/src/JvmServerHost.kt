package kinuvo.server.jvm

import kinuvo.server.rpc.serverRoutes
import kinuvo.gateways.mcp.McpAccess
import kinuvo.gateways.mcp.McpListener
import kinuvo.gateways.mcp.McpExecutionFactory
import kotlinx.coroutines.*
import kinuvo.server.execution.ExecutionStorage
import kinuvo.server.execution.remoteLinks
import kinuvo.server.plugin.TailcatServing
import kotlinx.io.files.Path
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import kinuvo.harness.acp.AcpExecutionFactory
import kinuvo.harness.remote.RemoteExecutionFactory
import kinuvo.harness.ExecutionState
import kinuvo.protocol.session.AttachmentData
import java.net.InetAddress
import kinuvo.server.ConnectionSecurity
import kinuvo.server.DeviceAuth
import kinuvo.server.NetworkListener
import kinuvo.server.NetworkSecurity
import kinuvo.server.ServerRuntime

class JvmServerHost(val harness: JvmAgentRuntime, val auth: DeviceAuth = DeviceAuth(File(harness.files.home.toString(), "server-devices.json")), private val name: String = machineName(), val network: NetworkSecurity = NetworkSecurity(), security: ConnectionSecurity = ConnectionSecurity(), private val tailcat: TailcatServing? = null) {
    private val mcpAccess = McpAccess()
    private val mcpListener = McpListener(mcpAccess)
    private val executionStorage = ExecutionStorage(harness.files.home)
    private val activity = ProcessActivity("Serving a live voice call")
    private val links = remoteLinks(harness.files.home)
    val runtime: ServerRuntime = ServerRuntime(harness, auth, serverRoutes(), name,
        remoteLinks = links, remoteHarness = { RemoteExecutionFactory(links::link, harness.serverId, it) }, security = security, prepareAttachment = ::prepareAttachment, keepAwake = activity::hold,
        executionRegistry = executionStorage.registry, executionFactory = McpExecutionFactory(AcpExecutionFactory(persistAttachment = ::persistExecutionAttachment), harness, mcpAccess, { mcpListener.url }), executionStorage = executionStorage)
    private suspend fun persistExecutionAttachment(state: ExecutionState, data: AttachmentData) = runtime.persistExecutionAttachment(state, data)
    private val listener = NetworkListener(runtime, auth, network)
    private var tailcatListener: NetworkListener? = null

    private val tailcatLock = Mutex()
    private var tailcatExposure: TailcatExposure? = null
    private var tailcatPort: Int? = null
    val tailcatEnabled get() = auth.tailcatAddress != null

    suspend fun shareTailcat(enabled: Boolean) = tailcatLock.withLock { setTailcat(enabled) }

    private suspend fun setTailcat(enabled: Boolean) {
        withContext(Dispatchers.IO + NonCancellable) {
            if (enabled && tailcatExposure == null) {
                val port = tailcatPort ?: startTailcatListener().also { tailcatPort = it }
                val exposure = TailcatExposure(auth, tailcatApi())
                try { exposure.start(tailcatState(), port); tailcatExposure = exposure }
                catch (e: Throwable) { exposure.close(); throw e }
            } else if (!enabled) {
                try { tailcatExposure?.close() } finally { tailcatExposure = null }
            }
        }
    }

    suspend fun resetTailcat() = tailcatLock.withLock {
        val enabled = tailcatEnabled
        setTailcat(false)
        withContext(Dispatchers.IO) { tailcatApi().reset(tailcatState()) }
        if (enabled) setTailcat(true)
    }

    private fun tailcatApi() = checkNotNull(tailcat) { "Tailcat is not available in this build" }

    fun tailcatState() = Path(harness.files.home.toString(), "tailcat")

    suspend fun startTailcatListener(): Int {
        check(tailcatListener == null)
        val listener = NetworkListener(runtime, auth, NetworkSecurity(trustedProxies = emptySet()), remoteOnly = true)
        val port = listener.start("127.0.0.1", emptyList())
        tailcatListener = listener
        return port
    }

    private var started = false
    private var advertised: LocalServer? = null

    suspend fun start(host: String = "0.0.0.0", port: Int = DEFAULT_PORT, schedule: Boolean = true): Int = start(host, listOf(port), schedule)
    suspend fun start(host: String, ports: List<Int>, schedule: Boolean = true): Int {
        check(!started) { "Server already started" }
        started = true
        try {
            mcpListener.start()
            runtime.start(schedule)
            return listener.start(host, ports)
        } catch (e: Throwable) {
            withContext(NonCancellable) {
                mcpListener.stop()
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
        tailcatLock.withLock { setTailcat(false) }
        tailcatListener?.stop()
        tailcatListener = null
        mcpListener.stop()
        listener.stop()
        runtime.close()
    }
}

/** What clients call this Server until they give it an alias. */
private fun machineName() = runCatching { InetAddress.getLocalHost().hostName.substringBefore('.') }.getOrNull()?.takeIf { it.isNotBlank() && it != "localhost" } ?: "kinuvo"
