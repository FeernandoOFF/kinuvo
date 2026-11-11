package kinuvo.server.embedded

import kinuvo.server.rpc.serverRoutes
import kinuvo.harness.AgentConfig
import kinuvo.server.plugin.HarnessPlugin
import kinuvo.server.plugin.exists
import kinuvo.server.devices.*
import kinuvo.harness.builtin.NativeExecutionFactory
import kinuvo.harness.builtin.plugin.ModelSource
import kinuvo.server.plugin.*
import kinuvo.server.execution.ExecutionStorage
import kinuvo.harness.remote.RemoteExecutionFactory
import kinuvo.server.execution.remoteLinks
import kinuvo.server.store.*
import kinuvo.protocol.tool.ToolNames
import kinuvo.protocol.*
import kinuvo.protocol.capability.*
import kinuvo.protocol.device.*
import kinuvo.protocol.tool.*
import kinuvo.protocol.agent.AgentId
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.io.files.Path
import kotlin.uuid.Uuid
import kinuvo.server.HarnessRuntime
import kinuvo.server.ServerInstallation
import kinuvo.server.ServerResources
import kinuvo.server.ServerRuntime
import kinuvo.server.providers.ModelProvider

class EmbeddedServerHost(val runtime: ServerRuntime, val token: String) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    suspend fun connect(): FrameConnection {
        val requests = Channel<Frame>(1024)
        val responses = Channel<Frame>(1024)
        val server = object : FrameConnection {
            override val incoming = requests.receiveAsFlow()
            override suspend fun send(frame: Frame) { responses.send(frame) }
            override suspend fun close() { requests.close(); responses.close() }
        }
        val job = scope.launch { runtime.serve(server) }
        return object : FrameConnection {
            override val incoming = responses.receiveAsFlow()
            override suspend fun send(frame: Frame) { requests.send(frame) }
            override suspend fun close() { requests.close(); job.cancelAndJoin(); responses.close() }
        }
    }
    suspend fun close() { runtime.close(); scope.cancel() }

    companion object {
        /**
         * The first model provider, from [providers] then [plugins], runs the default Agent on a fresh install.
         * A [continuous] Server keeps running and scheduling Routines while its app is in the background.
         */
        suspend fun create(home: Path, providers: List<ModelProvider> = emptyList(), plugins: List<HarnessPlugin> = emptyList(), modelSources: () -> List<ModelSource> = { emptyList() }, continuous: Boolean = false,
                           features: Set<ServerFeature> = setOf(ServerFeature.DeviceTools, ServerFeature.Computer),
                           peers: (PeerRegistry) -> PeerRegistry = { it }): EmbeddedServerHost {
            val files = AgentFiles(home)
            val fresh = try { !files.config(AgentId("default")).exists() } catch (e: Throwable) {
                ServerResources(plugins).close()
                throw e
            }
            val dependencies = ServerInstallation.assemble(files, plugins = plugins, modelProviders = providers, modelSources = modelSources)
            try {
                val harness = HarnessRuntime(dependencies, { NativeExecutionFactory(it, modelSources) }, builtInFeatures = features)
                if (fresh) {
                    val provider = harness.modelProviders.first()
                    val agent = harness.store.agents().single()
                    // Before sign-in a provider may not list its models; the Agent keeps the default one until the user picks.
                    val model = try { provider.models().first().id } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
                    harness.store.saveConfig(agent, if (model == null) AgentConfig(provider = provider.id, tools = ToolNames.DEFAULT)
                        else AgentConfig(provider = provider.id, model = model, reasoning = "none", tools = ToolNames.DEFAULT))
                }
                val token = Uuid.random().toString()
                val auth = peers(EmbeddedPeers(token))
                val executionStorage = ExecutionStorage(home)
                val links = remoteLinks(home)
                val runtime = ServerRuntime(harness, auth, serverRoutes(), "On this device", foregroundOnly = !continuous,
                    remoteLinks = links, remoteHarness = { RemoteExecutionFactory(links::link, harness.serverId, it) }, executionRegistry = executionStorage.registry,
                    executionStorage = executionStorage)
                runtime.start(schedule = continuous)
                return EmbeddedServerHost(runtime, token)
            } catch (e: Throwable) {
                try { dependencies.resources.close() } catch (cleanup: Throwable) { e.addSuppressed(cleanup) }
                throw e
            }
        }
    }
}

/** The app's own in-process client, which holds [token]; it is also this Device for Device tools. */
class EmbeddedPeers(private val token: String) : PeerRegistry {
    private val device = MutableStateFlow(ToolDevice("this-device", "This device", emptySet()))
    override fun authenticate(device: String, token: String?, pairingCode: String?): String? {
        if (token != this.token) return null
        this.device.update { it.copy(name = device) }
        return token
    }
    override fun valid(token: String) = token == this.token
    override fun toolDevices() = listOf(device.value)
    override fun setTools(token: String, tools: Set<DeviceToolKind>): String {
        require(token == this.token) { "Unknown embedded peer" }
        device.update { it.copy(tools = tools) }
        return device.value.id
    }
}
