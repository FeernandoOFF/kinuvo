package kinuvo.app.device

import kinuvo.app.connection.*

import kinuvo.app.computer.ComputerDriver
import kinuvo.client.KinuvoClient
import kinuvo.protocol.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import kotlin.coroutines.resume

/** Native readers use the wire JSON so Apple frameworks stay in Swift. Callbacks return once. */
interface DeviceReader {
    val kinds: List<String>
    fun authorize(kind: String, completion: (String?) -> Unit)
    fun read(requestJson: String, completion: (String) -> Unit)
}

data class DeviceAccessState(val enabled: Set<DeviceToolKind> = emptySet(), val busy: DeviceToolKind? = null, val error: String? = null)

class DeviceAccess(private val prefs: Preferences, val reader: DeviceReader, val computer: ComputerDriver? = null) {
    class Authorization(val kind: DeviceToolKind)
    private var authorization: Authorization? = null
    private val serializer = MapSerializer(String.serializer(), SetSerializer(DeviceToolKind.serializer()))
    private val saved = MutableStateFlow(runCatching { ProtocolJson.decodeFromString(serializer, prefs[KEY].orEmpty()) }.getOrDefault(emptyMap()))
    private var server: String? = null
    private var client: KinuvoClient? = null
    val supported get() = DeviceToolKind.entries.filter { it.name in reader.kinds || it == DeviceToolKind.Computer && computer != null }
    val state: StateFlow<DeviceAccessState>
        field = MutableStateFlow(DeviceAccessState())

    fun tools(server: String) = saved.value[server].orEmpty().intersect(supported.toSet())

    fun attach(server: String, client: KinuvoClient) {
        cancelAuthorization()
        this.server = server
        this.client = client
        state.value = DeviceAccessState(tools(server))
    }

    fun detach() { cancelAuthorization(); client = null; server = null; state.value = DeviceAccessState() }

    fun authorizing(kind: DeviceToolKind): Authorization {
        val attempt = Authorization(kind)
        authorization = attempt
        state.update { it.copy(busy = kind, error = null) }
        return attempt
    }

    fun cancelAuthorization() {
        authorization = null
        state.update { it.copy(busy = null) }
    }

    suspend fun authorized(attempt: Authorization, error: String?) {
        if (authorization !== attempt) return
        authorization = null
        setEnabled(attempt.kind, error == null, error)
    }

    suspend fun setEnabled(kind: DeviceToolKind, enabled: Boolean, error: String? = null) {
        if (error != null) { state.update { it.copy(busy = null, error = error) }; return }
        val server = server ?: return
        val tools = tools(server).let { if (enabled) it + kind else it - kind }
        saved.update { it + (server to tools) }
        prefs[KEY] = ProtocolJson.encodeToString(serializer, saved.value)
        state.update { it.copy(enabled = tools, busy = null, error = null) }
        val attached = client
        try { attached?.updateDeviceTools() }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { if (client !== attached) throw CancellationException("Server changed"); state.update { it.copy(error = "Saved on this Device; the Server will update when it reconnects.") } }
    }

    suspend fun read(server: String, request: DeviceToolRequest): DeviceToolResult {
        if (request.kind !in tools(server)) return DeviceToolResult(DeviceToolStatus.Denied, message = "This Device tool is disabled")
        require(request.limit in 1..20) { "Invalid result limit" }
        val attached = client
        val json = suspendCancellableCoroutine<String> { continuation ->
            reader.read(ProtocolJson.encodeToString(DeviceToolRequest.serializer(), request)) { if (continuation.isActive) continuation.resume(it) }
        }
        if (client !== attached) throw CancellationException("Server changed")
        if (request.kind !in tools(server)) return DeviceToolResult(DeviceToolStatus.Denied, message = "This Device tool was disabled during the read")
        val result = ProtocolJson.decodeFromString(DeviceToolResult.serializer(), json)
        return if (request.kind == DeviceToolKind.Sleep && result.status == DeviceToolStatus.Ready)
            result.copy(data = sleepSummary(result.data, requireNotNull(request.timeZone), request.start, request.end)) else result
    }

    /** Controls the screen for [server]'s Agents, when this Device allowed it. */
    suspend fun act(server: String, request: ComputerRequest): ComputerResult {
        val driver = computer
        if (driver == null || DeviceToolKind.Computer !in tools(server)) return ComputerResult(DeviceToolStatus.Denied, "Computer use is off on this Device")
        if (!driver.ready) return ComputerResult(DeviceToolStatus.Unavailable, "Turn on Kinuvo in this Device's Accessibility settings")
        return driver.perform(request)
    }

    private companion object { const val KEY = "device-tools" }
}
