package kinuvo.harness.server

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.websocket.Frame as WsFrame
import io.ktor.websocket.readText
import io.ktor.websocket.close
import kinuvo.client.KinuvoClient
import kinuvo.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.io.files.Path
import java.nio.file.Files
import kotlin.test.*

class ServerSecurityTest {
    @Test
    fun rotationDoesNotExposeAnInvalidCredentialToConcurrentValidation() = runBlocking {
        val home = Files.createTempDirectory("rotation-security")
        val auth = DeviceAuth(home.resolve("devices.json").toFile())
        val token = assertNotNull(auth.authenticate("owner", null, auth.currentPairingCode()))
        val invalidated = CompletableDeferred<String>()
        val release = java.util.concurrent.CountDownLatch(1)
        val peers = object : PeerRegistry by auth {
            override fun rotate(token: String) = auth.rotate(token).also {
                invalidated.complete(it)
                check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
            }
        }
        val runtime = ServerRuntime(Harness(Path(home.toString())), peers, security = ConnectionSecurity(validationIntervalMillis = 1))
        runtime.start()
        val requests = Channel<Frame>(Channel.UNLIMITED)
        val responses = Channel<Frame>(Channel.UNLIMITED)
        val serverConnection = object : FrameConnection {
            override val incoming = requests.receiveAsFlow()
            override suspend fun send(frame: Frame) { responses.send(frame) }
            override suspend fun close() { requests.close(); responses.close() }
        }
        val clientConnection = object : FrameConnection {
            override val incoming = responses.receiveAsFlow()
            override suspend fun send(frame: Frame) { requests.send(frame) }
            override suspend fun close() { requests.close(); responses.close() }
        }
        val serving = launch(Dispatchers.Default) { runtime.serve(serverConnection) }
        val client = KinuvoClient("local:rotation", "owner", token = token, connection = { clientConnection })
        try {
            client.connect()
            val rotated = async { client.rotateToken() }
            val replacement = withTimeout(5_000) { invalidated.await() }
            delay(100)
            assertIs<kinuvo.client.ConnectionState.Connected>(client.state.value)
            release.countDown()
            withTimeout(5_000) { rotated.await() }
            assertEquals(replacement, client.lastConnect?.token)
            assertFalse(auth.valid(token))
            assertTrue(client.call(Api.Agents, Unit).isNotEmpty())
        } finally { release.countDown(); client.close(); serving.cancelAndJoin(); runtime.close() }
    }

    @Test
    fun publicHostingRequiresLoopbackAndAFixedAvailablePort() = runBlocking {
        fun server() = HarnessServer(Harness(Path(Files.createTempDirectory("public-security").toString())), network = NetworkSecurity("https://kinuvo.example.com"))
        for ((host, port) in listOf("0.0.0.0" to 8787, "127.0.0.1" to 0)) {
            val server = server()
            try { assertFailsWith<IllegalArgumentException> { server.start(host, port, schedule = false) } }
            finally { server.stop() }
        }
        java.net.ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1")).use { occupied ->
            val server = server()
            try { assertFails { server.start("127.0.0.1", occupied.localPort, schedule = false) } }
            finally { server.stop() }
        }
        Unit
    }

    @Test
    fun ordinaryDevicesCannotManagePairingConfigurationOrApprovals() = runBlocking {
        val server = HarnessServer(Harness(Path(Files.createTempDirectory("server-security").toString())))
        val port = server.start("127.0.0.1", 0, schedule = false)
        val owner = KinuvoClient("ws://127.0.0.1:$port/ws", "owner", token = server.auth.sessionToken())
        var ordinary: KinuvoClient? = null
        try {
            owner.connect()
            val code = owner.call(Api.Pairing, PairingParams())
            ordinary = KinuvoClient("ws://127.0.0.1:$port/ws", "ordinary", pairingCode = code.code)
            assertFalse(ordinary.connect().management)
            assertTrue(ordinary.call(Api.Agents, Unit).isNotEmpty())
            assertFails { ordinary.call(Api.Pairing, PairingParams(management = true)) }
            assertFails { ordinary.call(Api.Approve, ApprovalParams("missing", ApprovalDecision.AllowOnce)) }
            assertFails { ordinary.call(Api.LoginProvider, ProviderRef("codex")) }
            ordinary.rotateToken()
            val current = ordinary.call(Api.PairedDevices, Unit).single { it.current }
            ordinary.call(Api.RevokeDevice, DeviceRef(current.id))
        } finally { ordinary?.close(); owner.close(); server.stop() }
    }

    @Test
    fun unauthenticatedConnectionsTimeOutAndAuthenticationFailuresClose() = runBlocking {
        val server = HarnessServer(Harness(Path(Files.createTempDirectory("ws-security").toString())), security = ConnectionSecurity(authenticationTimeoutMillis = 200))
        val port = server.start("127.0.0.1", 0, schedule = false)
        val http = HttpClient { install(WebSockets) }
        try {
            val idle = http.webSocketSession("ws://127.0.0.1:$port/ws")
            withTimeout(5_000) { idle.closeReason.await() }
            val rejected = http.webSocketSession("ws://127.0.0.1:$port/ws")
            rejected.send(WsFrame.Text(ProtocolJson.encodeToString(Frame.serializer(), Frame.Request("auth", Api.Connect.name,
                ProtocolJson.encodeToJsonElement(ConnectParams.serializer(), ConnectParams("attacker", pairingCode = "wrong"))))))
            val response = ProtocolJson.decodeFromString(Frame.serializer(), (rejected.incoming.receive() as WsFrame.Text).readText()) as Frame.Response
            assertFalse(response.ok)
            withTimeout(5_000) { rejected.closeReason.await() }
            Unit
        } finally { http.close(); server.stop() }
    }

    @Test
    fun queuedConnectCannotRetryAfterFailureAndOversizedPreauthFramesClose() = runBlocking {
        val server = HarnessServer(Harness(Path(Files.createTempDirectory("preauth-security").toString())))
        val port = server.start("127.0.0.1", 0, schedule = false)
        val http = HttpClient { install(WebSockets) }
        try {
            val code = server.auth.currentPairingCode()
            val socket = http.webSocketSession("ws://127.0.0.1:$port/ws")
            for ((id, secret) in listOf("bad" to "wrong", "retry" to code)) {
                socket.send(WsFrame.Text(ProtocolJson.encodeToString(Frame.serializer(), Frame.Request(id, Api.Connect.name,
                    ProtocolJson.encodeToJsonElement(ConnectParams.serializer(), ConnectParams("attacker", pairingCode = secret))))))
            }
            withTimeout(5_000) { socket.closeReason.await() }
            assertTrue(server.auth.devices().isEmpty())
            assertEquals(code, server.auth.pairingCode)
            val oversized = http.webSocketSession("ws://127.0.0.1:$port/ws")
            oversized.send(WsFrame.Text("x".repeat(4097)))
            withTimeout(5_000) { oversized.closeReason.await() }
            assertTrue(server.auth.devices().isEmpty())
            Unit
        } finally { http.close(); server.stop() }
    }

    @Test
    fun authenticatedMessageFloodClosesTheSocket() = runBlocking {
        val server = HarnessServer(Harness(Path(Files.createTempDirectory("message-security").toString())), security = ConnectionSecurity(messagesPerMinute = 2))
        val port = server.start("127.0.0.1", 0, schedule = false)
        val http = HttpClient { install(WebSockets) }
        try {
            val socket = http.webSocketSession("ws://127.0.0.1:$port/ws")
            socket.send(WsFrame.Text(ProtocolJson.encodeToString(Frame.serializer(), Frame.Request("auth", Api.Connect.name,
                ProtocolJson.encodeToJsonElement(ConnectParams.serializer(), ConnectParams("owner", token = server.auth.sessionToken()))))))
            assertTrue((ProtocolJson.decodeFromString(Frame.serializer(), (socket.incoming.receive() as WsFrame.Text).readText()) as Frame.Response).ok)
            socket.send(WsFrame.Text(ProtocolJson.encodeToString(Frame.serializer(), Frame.Request("one", Api.Agents.name))))
            socket.incoming.receive()
            socket.send(WsFrame.Text(ProtocolJson.encodeToString(Frame.serializer(), Frame.Request("two", Api.Agents.name))))
            withTimeout(5_000) { socket.closeReason.await() }
            Unit
        } finally { http.close(); server.stop() }
    }

    @Test
    fun browserCredentialsPersistRotateAndStayInSecureCookies() = runBlocking {
        val home = Files.createTempDirectory("browser-security")
        fun newServer() = HarnessServer(Harness(Path(home.toString())), network = NetworkSecurity("https://kinuvo.example.com"))
        var server = newServer()
        val available = java.net.ServerSocket(0).use { it.localPort }
        val port = server.start("127.0.0.1", available, schedule = false)
        val http = HttpClient { install(WebSockets) }
        suspend fun browserSocket(cookie: String) = http.webSocketSession("ws://127.0.0.1:$port/ws") {
            header("Origin", "https://kinuvo.example.com"); header("X-Forwarded-Proto", "https")
            header(HttpHeaders.Cookie, cookie.substringBefore(';'))
        }.also { socket ->
            socket.send(WsFrame.Text(ProtocolJson.encodeToString(Frame.serializer(), Frame.Request("auth", Api.Connect.name,
                ProtocolJson.encodeToJsonElement(ConnectParams.serializer(), ConnectParams("browser"))))))
            val response = ProtocolJson.decodeFromString(Frame.serializer(), (socket.incoming.receive() as WsFrame.Text).readText()) as Frame.Response
            assertTrue(response.ok)
            assertEquals("browser-session", ProtocolJson.decodeFromJsonElement(ConnectResult.serializer(), response.payload).token)
        }
        try {
            val code = server.auth.currentPairingCode()
            val forbidden = http.post("http://127.0.0.1:$port/auth/session") {
                header("Origin", "https://evil.test"); header("X-Forwarded-Proto", "https")
                setBody(ProtocolJson.encodeToString(ConnectParams.serializer(), ConnectParams("browser", pairingCode = code)))
            }
            assertEquals(HttpStatusCode.Forbidden, forbidden.status)
            assertEquals(code, server.auth.pairingCode)
            val accepted = http.post("http://127.0.0.1:$port/auth/session") {
                header("Origin", "https://kinuvo.example.com"); header("X-Forwarded-Proto", "https")
                header(HttpHeaders.ContentType, "application/json")
                setBody(ProtocolJson.encodeToString(ConnectParams.serializer(), ConnectParams("browser", pairingCode = code)))
            }
            assertEquals(HttpStatusCode.NoContent, accepted.status)
            assertEquals("", accepted.bodyAsText())
            val cookie = assertNotNull(accepted.headers[HttpHeaders.SetCookie])
            assertTrue(cookie.contains("HttpOnly", ignoreCase = true))
            assertTrue(cookie.contains("Secure", ignoreCase = true))
            assertTrue(cookie.contains("SameSite=Strict", ignoreCase = true))
            assertTrue(cookie.contains("__Host-kinuvo-session="))
            assertTrue(parseServerSetCookieHeader(cookie).maxAge in 7_775_990..7_776_000)
            val token = cookie.substringBefore(';').substringAfter('=')
            val id = server.auth.devices().single().id
            val socket = browserSocket(cookie)
            socket.send(WsFrame.Text(ProtocolJson.encodeToString(Frame.serializer(), Frame.Request("rotate", Api.RotateToken.name))))
            var denied: Frame
            do { denied = ProtocolJson.decodeFromString(Frame.serializer(), (socket.incoming.receive() as WsFrame.Text).readText()) }
            while (denied !is Frame.Response || denied.id != "rotate")
            assertFalse(denied.ok)
            assertTrue(server.auth.valid(token))
            socket.close()

            server.stop()
            server = newServer()
            server.start("127.0.0.1", port, schedule = false)
            assertTrue(server.auth.valid(token))
            val resumed = http.post("http://127.0.0.1:$port/auth/session") {
                header("Origin", "https://kinuvo.example.com"); header("X-Forwarded-Proto", "https")
                header(HttpHeaders.Cookie, cookie.substringBefore(';'))
                setBody(ProtocolJson.encodeToString(ConnectParams.serializer(), ConnectParams("browser")))
            }
            assertEquals(HttpStatusCode.NoContent, resumed.status)
            assertNull(resumed.headers[HttpHeaders.SetCookie], "Reloading must not extend expiry")
            val other = browserSocket(cookie)
            val foreignRotation = http.post("http://127.0.0.1:$port/auth/rotate") {
                header("Origin", "https://evil.test"); header("X-Forwarded-Proto", "https")
                header(HttpHeaders.Cookie, cookie.substringBefore(';'))
                setBody(ProtocolJson.encodeToString(ConnectParams.serializer(), ConnectParams("browser")))
            }
            assertEquals(HttpStatusCode.Forbidden, foreignRotation.status)
            assertTrue(server.auth.valid(token))
            val rotated = http.post("http://127.0.0.1:$port/auth/rotate") {
                header("Origin", "https://kinuvo.example.com"); header("X-Forwarded-Proto", "https")
                header(HttpHeaders.Cookie, cookie.substringBefore(';'))
                setBody(ProtocolJson.encodeToString(ConnectParams.serializer(), ConnectParams("browser")))
            }
            assertEquals(HttpStatusCode.NoContent, rotated.status)
            assertEquals("", rotated.bodyAsText())
            val replacementCookie = assertNotNull(rotated.headers[HttpHeaders.SetCookie])
            assertTrue(parseServerSetCookieHeader(replacementCookie).maxAge in 7_775_990..7_776_000)
            val replacement = replacementCookie.substringBefore(';').substringAfter('=')
            assertFalse(server.auth.valid(token))
            assertTrue(server.auth.valid(replacement))
            assertEquals(id, server.auth.devices().single().id)
            withTimeout(5_000) { other.closeReason.await() }
            val reconnected = browserSocket(replacementCookie)
            val replay = http.post("http://127.0.0.1:$port/auth/session") {
                header("Origin", "https://kinuvo.example.com"); header("X-Forwarded-Proto", "https")
                header(HttpHeaders.Cookie, cookie.substringBefore(';'))
                setBody(ProtocolJson.encodeToString(ConnectParams.serializer(), ConnectParams("browser")))
            }
            assertEquals(HttpStatusCode.Unauthorized, replay.status)
            server.auth.revoke(id)
            assertFalse(server.auth.valid(replacement))
            reconnected.send(WsFrame.Text(ProtocolJson.encodeToString(Frame.serializer(), Frame.Request("revoked", Api.Agents.name))))
            withTimeout(5_000) { reconnected.closeReason.await() }
            Unit
        } finally { http.close(); server.stop() }
    }
}
