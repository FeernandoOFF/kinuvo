package kinuvo.app

import kinuvo.app.connection.*
import kinuvo.protocol.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationActionsTest {
    private val prefs = MemoryPreferences()
    private val wire = Wire()
    private val servers = Servers(prefs, credentials = prefs.credentials, transport = { wire })
    private val actions = NotificationActions(servers, Device("phone"), UnconfinedTestDispatcher())
    private val reply = PushMessage("", "", "agent", "session", serverId = "server", kind = PushKind.Reply)

    private fun remember() = servers.remember(Server.Remote("wss://saved/ws"), "saved-token", "server", "Home")

    @Test fun replyUsesSavedCredentialsAndSessionWithoutSelectingServer() = runTest {
        remember()
        assertTrue(actions.perform(reply, NotificationAction.Reply("hello")))
        val connect = wire.requests.first { it.method == Api.Connect.name }
        assertEquals("saved-token", ProtocolJson.decodeFromJsonElement(Api.Connect.params, connect.params).token)
        val send = wire.requests.single { it.method == Api.Send.name }
        assertEquals(SendParams("agent", "session", "hello"), ProtocolJson.decodeFromJsonElement(Api.Send.params, send.params))
        assertNull(servers.current)
        assertFalse(ProtocolJson.decodeFromJsonElement(Api.Presence.params, wire.requests.first { it.method == Api.Presence.name }.params).active)
    }

    @Test fun approvalsAreAllowOnceAndPlainDeny() = runTest {
        remember()
        val push = reply.copy(kind = PushKind.Approval, approvalId = "approval")
        assertTrue(actions.perform(push, NotificationAction.Allow))
        assertTrue(actions.perform(push, NotificationAction.Deny))
        val params = wire.requests.filter { it.method == Api.Approve.name }.map { ProtocolJson.decodeFromJsonElement(Api.Approve.params, it.params) }
        assertEquals(listOf(ApprovalParams("approval", ApprovalDecision.AllowOnce), ApprovalParams("approval", ApprovalDecision.Deny())), params)
    }

    @Test fun malformedUnknownAndUnpairedMessagesDoNotConnect() = runTest {
        assertFalse(actions.perform(reply, NotificationAction.Reply("hello")))
        remember()
        assertFalse(actions.perform(reply, NotificationAction.Reply(" ")))
        assertFalse(actions.perform(reply.copy(sessionId = null), NotificationAction.Reply("hello")))
        assertFalse(actions.perform(reply.copy(kind = PushKind.Other), NotificationAction.Reply("hello")))
        assertFalse(actions.perform(reply, NotificationAction.Allow))
        servers.unpair(servers.find("server") as Server.Remote)
        assertFalse(actions.perform(reply, NotificationAction.Reply("hello")))
        assertEquals(emptyList(), wire.requests)
    }

    @Test fun rejectionAndWrongIdentityFail() = runTest {
        remember()
        wire.accept = false
        assertFalse(actions.perform(reply.copy(kind = PushKind.Approval, approvalId = "old"), NotificationAction.Deny))
        wire.serverId = "other"
        assertFalse(actions.perform(reply, NotificationAction.Reply("hello")))
        assertFalse(wire.requests.any { it.method == Api.Send.name })
        assertEquals("Couldn't reach Home, open Kinuvo", actions.failure("server"))
    }

    @Test fun embeddedServerAndTimeoutUseTheSamePath() = runTest {
        var slow = false
        val embedded = object : EmbeddedServer {
            override suspend fun endpoint(): Endpoint {
                if (slow) delay(30_000)
                return Endpoint("local:", "local", connection = { wire })
            }
        }
        val own = Servers(prefs, embedded, prefs.credentials)
        own.remember(Server.Embedded, "local", "server")
        val handler = NotificationActions(own, Device("phone"), UnconfinedTestDispatcher(testScheduler))
        assertTrue(handler.perform(reply, NotificationAction.Reply("hello")))
        slow = true
        assertFalse(handler.perform(reply, NotificationAction.Reply("hello")))
    }

    private class Wire : FrameConnection {
        val requests = mutableListOf<Frame.Request>()
        private val frames = Channel<Frame>(Channel.UNLIMITED)
        override val incoming = frames.receiveAsFlow()
        var accept = true
        var serverId = "server"
        override suspend fun send(frame: Frame) {
            val request = frame as Frame.Request
            requests += request
            when (request.method) {
                Api.Connect.name -> respond(request, Api.Connect, ConnectResult("saved-token", "Home", capabilities = ServerCapabilities(serverId), management = true))
                Api.Presence.name -> respond(request, Api.Presence, Unit)
                Api.Send.name -> respond(request, Api.Send, SendResult("session", "run", "hello"))
                Api.Approve.name -> respond(request, Api.Approve, accept)
                else -> error("Unexpected method")
            }
        }
        private suspend fun <P, R> respond(request: Frame.Request, method: Method<P, R>, value: R) {
            frames.send(Frame.Response(request.id, true, ProtocolJson.encodeToJsonElement(method.result, value)))
        }
        override suspend fun close() {}
    }
}
