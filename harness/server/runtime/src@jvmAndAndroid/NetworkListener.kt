package kinuvo.harness.server

import kinuvo.protocol.*
import io.ktor.server.application.install
import io.ktor.server.application.call
import io.ktor.server.request.receiveChannel
import io.ktor.server.request.header
import io.ktor.server.response.respondText
import io.ktor.server.response.header
import io.ktor.server.routing.post
import io.ktor.http.HttpStatusCode
import io.ktor.http.Cookie
import io.ktor.http.CookieEncoding
import io.ktor.utils.io.readBuffer
import kotlinx.io.readByteArray
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticResources
import io.ktor.http.ContentType
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.Frame as WsFrame
import io.ktor.websocket.readText
import io.ktor.websocket.close
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow

/** Serves [runtime] over WebSockets to Devices on the network, which pair and authenticate through [auth]. */
class NetworkListener(private val runtime: ServerRuntime, private val auth: DeviceAuth, val network: NetworkSecurity = NetworkSecurity()) {
    private val limits = NetworkLimits()
    private var engine: EmbeddedServer<*, *>? = null
    var boundPort = 0; private set
    var boundHost = "127.0.0.1"; private set

    /** Listens on the first free of [ports] (any port when none is); returns the port. */
    suspend fun start(host: String, ports: List<Int>): Int {
        check(engine == null) { "Already listening" }
        if (network.publicUrl != null) {
            require(java.net.InetAddress.getByName(host).isLoopbackAddress) { "Public hosting must listen on loopback behind a trusted HTTPS proxy" }
            require(ports.size == 1 && ports.single() in 1..65535) { "Public hosting requires a single fixed port" }
        }
        auth.publicUrl = network.publicUrl
        val server = if (network.publicUrl != null) listen(host, ports.single())
            else ports.filter { free(host, it) }.firstNotNullOfOrNull { port -> runCatching { listen(host, port) }.getOrNull() } ?: listen(host, 0)
        engine = server
        boundHost = host
        boundPort = server.engine.resolvedConnectors().first().port
        auth.port = boundPort
        val address = java.net.InetAddress.getByName(host)
        auth.addresses = {
            when {
                address.isAnyLocalAddress -> lanAddresses()
                address.isLoopbackAddress -> emptyList()
                else -> listOf(address.hostAddress)
            }
        }
        return boundPort
    }

    fun stop() { engine?.stop(1_000, 2_000); engine = null }

    private fun free(host: String, port: Int) = runCatching { java.net.ServerSocket().use { it.bind(java.net.InetSocketAddress(host, port)) } }.isSuccess

    private fun listen(host: String, port: Int): EmbeddedServer<*, *> {
        val server = embeddedServer(CIO, port = port, host = host) {
            install(WebSockets) { pingPeriodMillis = 15_000; maxFrameSize = PREAUTH_FRAME_SIZE }
            routing {
                for (rotate in listOf(false, true)) post(if (rotate) "/auth/rotate" else "/auth/session") {
                    call.response.header("Cache-Control", "no-store")
                    val peer = call.request.local.remoteAddress
                    val forwarded = call.request.header("X-Forwarded-Proto")
                    val hosts = (lanAddresses() + listOf("127.0.0.1", "localhost", "::1", host)).toSet()
                    if (call.request.header("Origin") == null || !network.acceptsOrigin(call.request.header("Origin"), call.request.header("Host").orEmpty(), hosts) ||
                        !(network.secureProxy(peer, forwarded) || network.publicUrl == null && java.net.InetAddress.getByName(peer).isLoopbackAddress)) {
                        limits.audit(peer, "browser_origin_or_transport")
                        call.respondText("Forbidden", status = HttpStatusCode.Forbidden); return@post
                    }
                    val source = runCatching { network.source(peer, call.request.header("X-Forwarded-For")) }.getOrNull()
                    if (source == null || call.request.cookies[SESSION_COOKIE]?.let(auth::cookieMaxAge) == null && !limits.authenticate(source)) { limits.audit(peer, "authentication_rate"); call.respondText("Try again later", status = HttpStatusCode.TooManyRequests); return@post }
                    if (!limits.open(source)) { call.respondText("Try again later", status = HttpStatusCode.TooManyRequests); return@post }
                    try {
                        val bytes = withTimeoutOrNull(10_000) { call.receiveChannel().readBuffer(PREAUTH_FRAME_SIZE + 1).readByteArray() }
                        if (bytes == null || bytes.size > PREAUTH_FRAME_SIZE) { call.respondText("Invalid request", status = HttpStatusCode.BadRequest); return@post }
                        val raw = runCatching { ProtocolJson.parseToJsonElement(bytes.decodeToString()) }.getOrNull()
                        val version = (raw as? kotlinx.serialization.json.JsonObject)?.get("protocolVersion")
                        if (version?.toString() != ProtocolVersion.Current.toString()) {
                            call.respondText(ProtocolVersion.Mismatch, status = HttpStatusCode.Conflict); return@post
                        }
                        val params = runCatching { ProtocolJson.decodeFromJsonElement(ConnectParams.serializer(), raw) }.getOrNull()
                        if (params == null) { call.respondText("Invalid request", status = HttpStatusCode.BadRequest); return@post }
                        val existing = call.request.cookies[SESSION_COOKIE]?.takeIf { auth.cookieMaxAge(it) != null }
                        if (!rotate && existing != null && params.pairingCode == null && params.token == null) {
                            call.respondText("", status = HttpStatusCode.NoContent); return@post
                        }
                        val token = if (rotate) existing?.let(runtime::rotateBrowserToken)
                            else auth.authenticate(params.device, params.token, params.pairingCode)
                        if (token == null) {
                            limits.audit(source, "authentication_failed")
                            call.respondText("Invalid or expired Device credential", status = HttpStatusCode.Unauthorized); return@post
                        }
                        val maxAge = auth.cookieMaxAge(token)
                        if (maxAge == null) { call.respondText("Invalid Device credential", status = HttpStatusCode.Unauthorized); return@post }
                        call.response.cookies.append(Cookie(SESSION_COOKIE, token, CookieEncoding.RAW, maxAge = maxAge,
                            path = "/", secure = true, httpOnly = true, extensions = mapOf("SameSite" to "Strict")))
                        call.respondText("", status = HttpStatusCode.NoContent)
                    } finally { limits.close(source) }
                }
                webSocket("/ws") {
                    val peer = call.request.local.remoteAddress
                    val hosts = (lanAddresses() + listOf("127.0.0.1", "localhost", "::1", host)).toSet()
                    if (!network.acceptsOrigin(call.request.header("Origin"), call.request.header("Host").orEmpty(), hosts) ||
                        network.publicUrl != null && call.request.header("Origin") != null && !network.secureProxy(peer, call.request.header("X-Forwarded-Proto"))) { limits.audit(peer, "websocket_origin_or_transport"); close(); return@webSocket }
                    val source = runCatching { network.source(peer, call.request.header("X-Forwarded-For")) }.getOrNull()
                    if (source == null || !limits.open(source)) { limits.audit(peer, "connection_limit"); close(); return@webSocket }
                    try {
                        val browser = call.request.header("Origin") != null
                        val credential = if (browser) call.request.cookies[SESSION_COOKIE]?.takeIf { auth.cookieMaxAge(it) != null } else null
                        if (browser && credential == null) { close(); return@webSocket }
                        runtime.serve(connection(credential) { frame ->
                            val params = (frame as? Frame.Request)?.takeIf { it.method == Api.Connect.name }?.let {
                                runCatching { ProtocolJson.decodeFromJsonElement(ConnectParams.serializer(), it.params) }.getOrNull()
                            }
                            credential?.let(auth::valid) == true || params?.token?.let(auth::valid) == true || limits.authenticate(source)
                        })
                    } finally { limits.close(source) }
                }
                staticResources("/", "web") {
                    enableAutoHeadResponse()
                    contentType { url -> when (url.path.substringAfterLast('.')) {
                        "wasm" -> ContentType("application", "wasm")
                        "mjs" -> ContentType.Application.JavaScript
                        else -> null
                    } }
                }
            }
        }
        return try { server.start(wait = false) } catch (e: Exception) { server.stop(0, 0); throw e }
    }
}

private fun WebSocketSession.connection(credential: String?, authenticate: (Frame) -> Boolean): FrameConnection = object : FrameConnection {
    override val credential = credential
    private var authenticated = false
    override val incoming = flow {
        for (frame in this@connection.incoming) {
            if (frame !is WsFrame.Text) { this@connection.close(); break }
            val text = frame.readText()
            if (!authenticated && text.length > PREAUTH_FRAME_SIZE) { this@connection.close(); break }
            val decoded = runCatching { ProtocolJson.decodeFromString(Frame.serializer(), text) }.getOrNull()
            if (decoded == null || !authenticated && !authenticate(decoded)) { this@connection.close(); break }
            emit(decoded)
        }
    }
    override suspend fun send(frame: Frame) {
        val outgoing = if (frame is Frame.Response && !authenticated && frame.ok) {
            authenticated = true
            this@connection.maxFrameSize = 15 * 1024 * 1024L
            if (credential != null) frame.copy(payload = ProtocolJson.encodeToJsonElement(ConnectResult.serializer(),
                ProtocolJson.decodeFromJsonElement(ConnectResult.serializer(), frame.payload).copy(token = "browser-session"))) else frame
        } else frame
        this@connection.send(WsFrame.Text(ProtocolJson.encodeToString(Frame.serializer(), outgoing)))
    }
    override suspend fun close() { this@connection.close() }
}

private const val PREAUTH_FRAME_SIZE = 4096L
private const val SESSION_COOKIE = "__Host-kinuvo-session"


