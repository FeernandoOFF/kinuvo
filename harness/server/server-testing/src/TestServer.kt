package kinuvo.harness.server.testing

import kinuvo.client.KinuvoClient
import kinuvo.harness.core.NativeExecutionFactory
import kinuvo.server.plugin.HarnessPlugin
import kinuvo.harness.core.store.AgentFiles
import kinuvo.harness.core.store.knowledgeConfig
import kinuvo.harness.core.store.saveKnowledgeConfig
import kinuvo.harness.server.DeviceAuth
import kinuvo.harness.server.HarnessRuntime
import kinuvo.harness.server.NetworkListener
import kinuvo.harness.server.ServerInstallation
import kinuvo.harness.server.ServerRuntime
import kinuvo.harness.server.execution.ExecutionRegistry
import kinuvo.harness.server.execution.MemoryExecutionRecovery
import kinuvo.harness.testing.ModelScript
import kinuvo.protocol.AgentRef
import kinuvo.protocol.Api
import kinuvo.protocol.ComputerRequest
import kinuvo.protocol.ComputerResult
import kinuvo.protocol.DeviceToolKind
import kinuvo.protocol.DeviceToolStatus
import kinuvo.protocol.Frame
import kinuvo.protocol.FrameConnection
import kinuvo.protocol.SendParams
import kinuvo.protocol.SessionInfo
import kinuvo.protocol.UpdateSessionParams
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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
    val harnesses: List<FakeHarness>,
    val prompts: PromptLog,
    private val temporary: Boolean,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val clients = mutableListOf<KinuvoClient>()
    private var listener: NetworkListener? = null
    private val manager by lazy {
        KinuvoClient("memory://test-server", "test-manager", token = auth.sessionToken(), timeZone = "UTC", connection = { open(record = false) })
            .also { synchronized(clients) { clients += it } }
    }
    private var managing = false

    val spy = RequestSpy()

    /** The code a new Device pairs with; asking again after one pairs gives a fresh one. */
    val pairingCode: String get() = auth.currentPairingCode()

    /** Pairs a Device and returns its token, for clients that start already authenticated. */
    fun pairDevice(name: String = "test-device"): String =
        requireNotNull(auth.authenticate(name, null, pairingCode)) { "Pairing was refused" }

    /** A connection wired straight to the runtime; the Server sees what [spy] records. */
    fun connect(): FrameConnection = open(record = true)

    private fun open(record: Boolean): FrameConnection {
        val requests = Channel<Frame>(Channel.UNLIMITED)
        val responses = Channel<Frame>(Channel.UNLIMITED)
        val server = object : FrameConnection {
            override val incoming = requests.receiveAsFlow().onEach { if (record) spy.record(it) }
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
    fun client(
        device: String = "test-device",
        token: String? = null,
        deviceTools: () -> Set<DeviceToolKind> = { emptySet() },
        computer: suspend (ComputerRequest) -> ComputerResult = { ComputerResult(DeviceToolStatus.Unsupported) },
    ): KinuvoClient =
        KinuvoClient("memory://test-server", device, token = token, pairingCode = if (token == null) pairingCode else null,
            timeZone = "UTC", deviceTools = deviceTools, computer = computer, connection = { connect() })
            .also { synchronized(clients) { clients += it } }

    /**
     * Starts a Session of Agent [agentId] through the Server, as a management Device would, unseen by [spy]. With a
     * [prompt] the turn starts at once, so a script that waits for an Approval or a question leaves it pending.
     */
    suspend fun startSession(agentId: String, prompt: String? = null, title: String? = null): SessionInfo {
        if (!managing) { manager.connect(); managing = true }
        val session = manager.call(Api.CreateSession, AgentRef(agentId))
        if (prompt == null) return session
        manager.call(Api.Send, SendParams(agentId, session.id, prompt))
        return if (title == null) session else manager.call(Api.UpdateSession, UpdateSessionParams(agentId, session.id, title = title))
    }

    /** Starts listening on loopback, on [port] if it is free and otherwise any port, and returns the WebSocket URL; clients on it are not seen by [spy]. */
    suspend fun listen(port: Int? = null): String {
        val network = listener ?: NetworkListener(runtime, auth).also { listener = it }
        val bound = network.start("127.0.0.1", listOfNotNull(port))
        return "ws://127.0.0.1:$bound/ws"
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
         * model's answers, [plugins] adds what a test needs beyond the built-in tools, Tasks and Knowledge, and [harnesses]
         * are the external harnesses the Server can run Agents on, once [scenario] registers them. With [schedule] the Server also
         * runs Routines and Tasks; [prompts] records what the built-in harness is asked to run.
         */
        suspend fun start(
            home: Path? = null,
            plugins: List<HarnessPlugin> = emptyList(),
            script: ModelScript.() -> Unit = {},
            harnesses: List<FakeHarness> = emptyList(),
            schedule: Boolean = false,
            scenario: suspend Scenario.() -> Unit = {},
        ): TestServer {
            val directory = home ?: Path(Files.createTempDirectory("kinuvo-test-server").toString())
            val files = AgentFiles(directory)
            files.saveKnowledgeConfig(files.knowledgeConfig().copy(reflection = false))
            val modelScript = ModelScript().apply(script)
            val dependencies = ServerInstallation.assemble(files, plugins = plugins, modelSources = { plugins.filterIsInstance<FakeModels>().flatMap { it.modelSources } }, userHome = directory)
            val prompts = PromptLog()
            val harness = HarnessRuntime(dependencies, modelFactory = { modelScript.model }, nativeSessions = { NativeExecutionFactory(it).recording(prompts) })
            val registry = ExecutionRegistry()
            val recovery = MemoryExecutionRecovery()
            try {
                Scenario(harness, directory, registry, recovery).scenario()
                val auth = DeviceAuth(File(directory.toString(), "server-devices.json"))
                val runtime = ServerRuntime(harness, auth, "Test Server", executionRegistry = registry, executionStorage = recovery,
                    executionFactory = harnesses.takeIf { it.isNotEmpty() }?.let(::FakeHarnesses))
                runtime.start(schedule = schedule)
                withContext(Dispatchers.Default) {
                    withTimeout(5_000) { while (registry.catalog().connections.any { it.readiness.reasonCode == "checking" }) delay(10) }
                }
                return TestServer(directory, harness, runtime, auth, modelScript, harnesses, prompts, temporary = home == null)
            } catch (e: Throwable) {
                try { harness.close() } catch (cleanup: Throwable) { e.addSuppressed(cleanup) }
                throw e
            }
        }
    }
}
