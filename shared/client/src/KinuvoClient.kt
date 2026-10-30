package kinuvo.client

import kinuvo.protocol.*
import kotlin.concurrent.Volatile
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch

sealed interface ConnectionState {
    data object Connecting : ConnectionState
    data class Connected(val serverName: String) : ConnectionState
    /** [rejected] when the Server refused the token, e.g. because the Device was revoked; it won't reconnect. */
    data class Disconnected(val reason: String, val rejected: Boolean = false) : ConnectionState
}

/** Thrown when the server rejects the token or pairing code; retrying won't help. */
class AuthException(message: String) : Exception(message)

class ProtocolMismatchException : Exception(ProtocolVersion.Mismatch)

/** A request that failed because the connection is down; the client retries on its own. */
class DisconnectedException(message: String = "Disconnected") : IllegalStateException(message)

/**
 * A connection to a Kinuvo server: typed request/response calls plus server events.
 * Reconnects automatically with the token received on the first successful connect.
 */
@OptIn(ExperimentalAtomicApi::class)
class KinuvoClient(
    private val url: String,
    private val device: String,
    token: String? = null,
    private val pairingCode: String? = null,
    private val onToken: (String) -> Unit = {},
    private val timeZone: String? = null,
    private val deviceTools: () -> Set<DeviceToolKind> = { emptySet() },
    private val deviceRead: suspend (DeviceToolRequest) -> DeviceToolResult = { DeviceToolResult(DeviceToolStatus.Unsupported) },
    /** Controls this Device's screen; unlike reads, it works while the app is in the background. */
    private val computer: suspend (ComputerRequest) -> ComputerResult = { ComputerResult(DeviceToolStatus.Unsupported) },
    private val connection: (suspend () -> FrameConnection)? = null,
    /** Lets a public Server be reached over plain ws://, e.g. a linked Server without TLS. */
    private val allowCleartext: Boolean = false,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val http by lazy { httpClient { install(WebSockets) { maxFrameSize = MAX_FRAME_SIZE } } }
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val pending = mutableMapOf<String, CompletableDeferred<Frame.Response>>()
    private val ids = AtomicLong(0)
    private val _events = MutableSharedFlow<Frame.Event>(extraBufferCapacity = 1024)
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Connecting)
    private var session: FrameConnection? = null
    private var token: String? = token
    private var loop: Job? = null
    @Volatile private var active = true
    private val presence = Mutex()
    /** Keeps within the Server's limit, leaving one for a voice cancel sent while its request is still open. */
    private val slots = Semaphore(MAX_CONCURRENT_REQUESTS - 1)

    val state: StateFlow<ConnectionState> = _state
    val events: SharedFlow<Frame.Event> = _events
    var lastConnect: ConnectResult? = null; private set

    /** Replaces this Device's credential while keeping its identity. */
    suspend fun rotateToken() {
        if (token == "browser-session" && connection == null) {
            loop?.cancelAndJoin()
            try { http.prepareConnect(url, ConnectParams(device), rotate = true) }
            catch (e: Exception) { currentCoroutineContext().ensureActive(); connect(); throw e }
            connect()
            return
        }
        token = call(Api.RotateToken, Unit).also(onToken)
        lastConnect = lastConnect?.copy(token = requireNotNull(token))
    }

    /** Connects (pairing on first use) and keeps the connection alive until [close]. Throws [AuthException] on bad credentials, or whatever stopped the first attempt. */
    suspend fun connect(): ConnectResult {
        val first = CompletableDeferred<ConnectResult>()
        loop = scope.launch {
            var delayMs = 500L
            while (isActive) {
                _state.value = ConnectionState.Connecting
                var socket: FrameConnection? = null
                var reader: Job? = null
                try {
                    val params = ConnectParams(device, token, if (token == null) pairingCode else null, timeZone, deviceTools())
                    val prepared = if (connection == null) { requireSecureTransport(url, allowCleartext); http.prepareConnect(url, params) } else params
                    val ws = connection?.invoke() ?: http.connection(url)
                    socket = ws
                    session = ws
                    reader = launch {
                        try { read(ws) }
                        catch (e: CancellationException) { throw e }
                        catch (_: Exception) { }
                        finally { withContext(NonCancellable) { if (session === ws) failPending() } }
                    }
                    val result = request(Api.Connect, prepared)
                    token = result.token.also(onToken)
                    lastConnect = result
                    _state.value = ConnectionState.Connected(result.serverName)
                    presence.withLock { request(Api.Presence, PresenceParams(active)) }
                    first.complete(result)
                    delayMs = 500L
                    reader.join()
                    _state.value = ConnectionState.Disconnected("Connection lost")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ProtocolMismatchException) {
                    _state.value = ConnectionState.Disconnected(e.message.orEmpty())
                    first.completeExceptionally(e)
                    return@launch
                } catch (e: AuthException) {
                    _state.value = ConnectionState.Disconnected(e.message ?: "Unauthorized", rejected = true)
                    first.completeExceptionally(e)
                    return@launch
                } catch (e: Exception) {
                    _state.value = ConnectionState.Disconnected(e.message ?: e.toString())
                    if (!first.isCompleted) { first.completeExceptionally(e); return@launch }
                } finally {
                    withContext(NonCancellable) {
                        reader?.cancelAndJoin()
                        runCatching { socket?.close() }
                        if (session === socket) session = null
                        failPending()
                    }
                }
                delay(delayMs)
                delayMs = (delayMs * 2).coerceAtMost(10_000)
            }
        }
        return first.await()
    }

    suspend fun <P, R> call(method: Method<P, R>, params: P): R = request(method, params)

    suspend fun updateDeviceTools() = call(Api.DeviceTools, DeviceToolsParams(deviceTools()))

    /** Whether the app is in the foreground; while it isn't, the Server notifies instead of counting this client as watching. */
    fun setActive(active: Boolean) {
        if (this.active == active) return
        this.active = active
        // One at a time, each sending the latest value, so the Server ends up with the last one.
        scope.launch { presence.withLock { runCatching { request(Api.Presence, PresenceParams(this@KinuvoClient.active)) } } }
    }

    fun <T> events(type: EventType<T>): Flow<T> =
        _events.filter { it.event == type.name }.map { ProtocolJson.decodeFromJsonElement(type.payload, it.payload) }

    fun close() {
        loop?.cancel()
        scope.launch { runCatching { session?.close() } }.invokeOnCompletion { scope.cancel(); if (connection == null) http.close() }
    }

    private suspend fun <P, R> request(method: Method<P, R>, params: P): R =
        if (method == Api.Connect) exchange(method, params) else slots.withPermit { exchange(method, params) }

    private suspend fun <P, R> exchange(method: Method<P, R>, params: P): R {
        val ws = session ?: throw DisconnectedException("Not connected")
        val id = ids.incrementAndFetch().toString()
        val deferred = CompletableDeferred<Frame.Response>()
        synchronizedPending { pending[id] = deferred }
        val frame = Frame.Request(id, method.name, ProtocolJson.encodeToJsonElement(method.params, params))
        val response = try {
            ws.send(frame)
            deferred.await()
        } catch (e: CancellationException) {
            if (method == Api.Transcribe || method == Api.Synthesize) withContext(NonCancellable) {
                withTimeoutOrNull(5_000) {
                    if (session === ws) runCatching {
                        ws.send(Frame.Request("$id:cancel", Api.CancelVoice.name,
                            ProtocolJson.encodeToJsonElement(Api.CancelVoice.params, CancelVoiceParams(id))))
                    }
                }
            }
            throw e
        } finally {
            withContext(NonCancellable) { synchronizedPending { pending.remove(id) } }
        }
        if (!response.ok) {
            val message = response.error ?: "Request failed"
            if (method == Api.Connect && message.startsWith("protocol_mismatch:")) throw ProtocolMismatchException()
            throw if (method == Api.Connect) AuthException(message) else IllegalStateException(message)
        }
        if (method == Api.Connect) {
            val version = (response.payload as? kotlinx.serialization.json.JsonObject)?.get("protocolVersion")
            if (version?.toString() != ProtocolVersion.Current.toString()) throw ProtocolMismatchException()
        }
        return ProtocolJson.decodeFromJsonElement(method.result, response.payload)
    }

    private suspend fun read(ws: FrameConnection) = coroutineScope {
        try {
            ws.incoming.collect { f ->
                when (f) {
                    is Frame.Response -> synchronizedPending { pending.remove(f.id) }?.complete(f)
                    is Frame.Event -> _events.emit(f)
                    is Frame.Request -> launch {
                        val response = try {
                            when (f.method) {
                                Api.DeviceRead.name -> {
                                    val p = ProtocolJson.decodeFromJsonElement(Api.DeviceRead.params, f.params)
                                    val result = if (active && _state.value is ConnectionState.Connected && session === ws) deviceRead(p)
                                        else DeviceToolResult(DeviceToolStatus.Unavailable, message = "Open Kinuvo on this Device")
                                    Frame.Response(f.id, true, ProtocolJson.encodeToJsonElement(Api.DeviceRead.result, result))
                                }
                                Api.ComputerAct.name -> {
                                    val result = computer(ProtocolJson.decodeFromJsonElement(Api.ComputerAct.params, f.params))
                                    Frame.Response(f.id, true, ProtocolJson.encodeToJsonElement(Api.ComputerAct.result, result))
                                }
                                else -> error("Unsupported Device method")
                            }
                        } catch (e: CancellationException) { throw e }
                        catch (_: Exception) { Frame.Response(f.id, false, error = "Device read failed") }
                        try { ws.send(response) }
                        catch (e: CancellationException) { throw e }
                        catch (_: Exception) { }
                    }
                }
            }
        } finally { coroutineContext.cancelChildren() }
    }

    private suspend fun failPending() {
        val all = synchronizedPending { pending.values.toList().also { pending.clear() } }
        all.forEach { it.completeExceptionally(DisconnectedException()) }
    }

    private val lock = Mutex()
    private suspend fun <T> synchronizedPending(block: () -> T): T = lock.withLock { block() }
}
