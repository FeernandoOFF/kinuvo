package kinuvo.harness.server.testing

import kinuvo.client.KinuvoClient
import kinuvo.harness.core.plugin.HarnessPlugin
import kinuvo.harness.core.store.AgentFiles
import kinuvo.harness.core.store.knowledgeConfig
import kinuvo.harness.core.store.saveKnowledgeConfig
import kinuvo.harness.server.DeviceAuth
import kinuvo.harness.server.HarnessRuntime
import kinuvo.harness.server.NetworkListener
import kinuvo.harness.server.ServerInstallation
import kinuvo.harness.server.ServerRuntime
import kinuvo.harness.testing.ModelScript
import kinuvo.protocol.Frame
import kinuvo.protocol.FrameConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import java.io.File
import java.nio.file.Files

/**
 * The real [ServerRuntime] on a temporary home, with a [ModelScript] for a model and no platform plugins. Clients reach it
 * through in-memory [FrameConnection]s (recorded by [spy]) or, for transport tests, a real WebSocket from [listen].
 */
class TestServer private constructor(
    val home: Path,
    val harness: HarnessRuntime,
    val runtime: ServerRuntime,
    val auth: DeviceAuth,
    val script: ModelScript,
    private val temporary: Boolean,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val clients = mutableListOf<KinuvoClient>()
    private var listener: NetworkListener? = null

    val spy = RequestSpy()

    /** The code a new Device pairs with; asking again after one pairs gives a fresh one. */
    val pairingCode: String get() = auth.currentPairingCode()

    /** Pairs a Device and returns its token, for clients that start already authenticated. */
    fun pairDevice(name: String = "test-device"): String =
        requireNotNull(auth.authenticate(name, null, pairingCode)) { "Pairing was refused" }

    /** A connection wired straight to the runtime; the Server sees what [spy] records. */
    fun connect(): FrameConnection {
        val requests = Channel<Frame>(Channel.UNLIMITED)
        val responses = Channel<Frame>(Channel.UNLIMITED)
        val server = object : FrameConnection {
            override val incoming = requests.receiveAsFlow().onEach(spy::record)
            override suspend fun send(frame: Frame) { responses.send(frame) }
            override suspend fun close() { requests.close(); responses.close() }
        }
        val serving = scope.launch { runtime.serve(server) }
        return object : FrameConnection {
            override val incoming = responses.receiveAsFlow()
            override suspend fun send(frame: Frame) { requests.send(frame) }
            override suspend fun close() { requests.close(); serving.cancelAndJoin(); responses.close() }
        }
    }

    /** A client over [connect] that pairs with [pairingCode], or authenticates with [token]; call `connect()` on it. */
    fun client(device: String = "test-device", token: String? = null): KinuvoClient =
        KinuvoClient("memory://test-server", device, token = token, pairingCode = if (token == null) pairingCode else null,
            timeZone = "UTC", connection = { connect() }).also { synchronized(clients) { clients += it } }

    /** Starts listening on a loopback port and returns the WebSocket URL; clients on it are not seen by [spy]. */
    suspend fun listen(): String {
        val network = listener ?: NetworkListener(runtime, auth).also { listener = it }
        val port = network.start("127.0.0.1", emptyList())
        return "ws://127.0.0.1:$port/ws"
    }

    suspend fun stop() {
        synchronized(clients) { clients.toList() }.forEach { it.close() }
        listener?.stop()
        runtime.close()
        scope.cancel()
        if (temporary) File(home.toString()).deleteRecursively()
    }

    override fun close() = runBlocking { stop() }

    companion object {
        /**
         * Starts a Server on a fresh temporary home (or [home]), seeded by [scenario] before it serves. [script] sets up the
         * model's answers, and [plugins] adds what a test needs beyond the built-in tools, Tasks and Knowledge.
         */
        suspend fun start(
            home: Path? = null,
            plugins: List<HarnessPlugin> = emptyList(),
            script: ModelScript.() -> Unit = {},
            scenario: suspend Scenario.() -> Unit = {},
        ): TestServer {
            val directory = home ?: Path(Files.createTempDirectory("kinuvo-test-server").toString())
            val files = AgentFiles(directory)
            files.saveKnowledgeConfig(files.knowledgeConfig().copy(reflection = false))
            val modelScript = ModelScript().apply(script)
            val dependencies = ServerInstallation.assemble(files, plugins = plugins, userHome = directory)
            val harness = HarnessRuntime(dependencies, modelFactory = { modelScript.model })
            try {
                Scenario(harness).scenario()
                val auth = DeviceAuth(File(directory.toString(), "server-devices.json"))
                val runtime = ServerRuntime(harness, auth, "Test Server")
                runtime.start(schedule = false)
                return TestServer(directory, harness, runtime, auth, modelScript, temporary = home == null)
            } catch (e: Throwable) {
                try { harness.close() } catch (cleanup: Throwable) { e.addSuppressed(cleanup) }
                throw e
            }
        }
    }
}
