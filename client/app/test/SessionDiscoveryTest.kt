package kinuvo.app

import androidx.lifecycle.ViewModelStore
import kinuvo.app.connection.*
import kinuvo.app.device.*
import kinuvo.app.navigation.Route
import kinuvo.app.session.SessionViewModel
import kinuvo.client.KinuvoClient
import kinuvo.protocol.*
import kinuvo.protocol.agent.*
import kinuvo.protocol.capability.ServerCapabilities
import kinuvo.protocol.connection.ConnectResult
import kinuvo.protocol.session.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class SessionDiscoveryTest {
    @Test fun composerOptsInOnlyWhenCreatingTheSession() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val frames = Channel<Frame>(Channel.UNLIMITED)
        val sent = mutableListOf<SendParams>()
        val agent = AgentId("agent")
        val session = SessionId("session")
        val wire = object : FrameConnection {
            override val incoming = frames.receiveAsFlow()
            override suspend fun send(frame: Frame) {
                if (frame !is Frame.Request) return
                val payload = when (frame.method) {
                    Api.Connect.name -> ProtocolJson.encodeToJsonElement(ConnectResult("token", "Server", capabilities = ServerCapabilities("server"), management = true))
                    Api.Agents.name -> ProtocolJson.encodeToJsonElement(Api.Agents.result, listOf(AgentInfo(agent, "Agent", "model")))
                    Api.CreateSession.name, Api.Session.name -> ProtocolJson.encodeToJsonElement(SessionInfo(session, agent, "Chat"))
                    Api.Send.name -> {
                        sent += ProtocolJson.decodeFromJsonElement(Api.Send.params, frame.params)
                        ProtocolJson.encodeToJsonElement(SendResult(session, RunId("run"), sent.last().text))
                    }
                    else -> buildJsonObject {}
                }
                frames.send(Frame.Response(frame.id, true, payload))
            }
            override suspend fun close() { frames.close() }
        }
        val client = KinuvoClient("local:test", "test", connection = { wire }, dispatcher = dispatcher)
        val reader = object : DeviceReader {
            override val kinds = emptyList<String>()
            override fun authorize(kind: String, completion: (String?) -> Unit) = completion(null)
            override fun read(requestJson: String, completion: (String) -> Unit) {}
        }
        val server = ConnectedServer(Notifications(emptyList()), DeviceAccess(MemoryPreferences(), reader))
        val store = ViewModelStore()
        try {
            client.connect()
            server.open(client, "local:test")
            val composer = SessionViewModel(server.sessions, Route.Chat(agent))
            store.put("composer", composer)
            runCurrent()
            composer.send("hello")
            runCurrent()
            assertEquals(1, sent.size)
            assertTrue(sent.single().discovery)
            composer.send("next")
            runCurrent()
            assertEquals(2, sent.size)
            assertFalse(sent.last().discovery)
            val existing = SessionViewModel(server.sessions, Route.Chat(agent, session))
            store.put("existing", existing)
            runCurrent()
            existing.send("existing")
            runCurrent()
            assertEquals(3, sent.size)
            assertFalse(sent.last().discovery)
        } finally { store.clear(); server.close(); client.close(); Dispatchers.resetMain() }
    }
}
