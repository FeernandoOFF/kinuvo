package kinuvo.harness.server

import ai.koog.prompt.streaming.StreamFrame
import kinuvo.client.KinuvoClient
import kinuvo.harness.codex.CodexAuth
import kinuvo.harness.codex.CodexProvider
import kinuvo.harness.core.PrivateFile
import kinuvo.harness.testing.scriptedModel
import kinuvo.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.io.files.Path
import java.nio.file.Files
import kotlin.test.*

class RoomsApiTest {
    @Test fun roomApiPersistsPublicHistoryAndLeavesDirectApiCompatible() = runBlocking {
        val home = Path(Files.createTempDirectory("rooms-api").toString())
        fun harness() = Harness(home = home, providers = listOf(CodexProvider(CodexAuth(PrivateFile(Path(home, "no-auth.json"))))), modelFactory = {
            scriptedModel { _, tools ->
                val reply = tools.firstOrNull { it.name.contains("public_reply") }
                if (reply == null) flowOf(StreamFrame.TextDelta("Public reply"), StreamFrame.End())
                else flowOf(StreamFrame.ToolCallComplete("publish", reply.name, """{"text":"Public reply"}"""), StreamFrame.End())
            }
        })
        val first = harness()
        first.store.createAgent("other")
        val server = HarnessServer(first)
        val port = server.start("127.0.0.1", 0)
        lateinit var room: Room
        lateinit var session: RoomSession
        val client = KinuvoClient("ws://127.0.0.1:$port/ws", "test", token = server.auth.sessionToken())
        try {
            withTimeout(15_000) {
                client.connect()
                val direct = client.call(Api.CreateSession, AgentRef("default"))
                client.call(Api.Send, SendParams("default", direct.id, "ordinary chat"))
                while (first.store.entries(first.store.agents().first { it.id == "default" }, direct.id).none { it is SessionEntry.AssistantMessage }) delay(10)
                room = client.call(Api.CreateRoom, CreateRoomParams("Team", listOf("default", "other"), "default"))
                session = client.call(Api.CreateRoomSession, RoomRef(room.id))
                client.call(Api.SendRoom, SendRoomParams(room.id, session.id, "Room message", listOf("other")))
                var view = client.call(Api.RoomSession, RoomSessionRef(room.id, session.id))
                while (view.running) { delay(10); view = client.call(Api.RoomSession, RoomSessionRef(room.id, session.id)) }
                assertNull(view.error)
                assertEquals(listOf(null, "other"), view.session.messages.map { it.sender })
                assertEquals("Public reply", view.session.messages.last().text)
                assertEquals(listOf(direct.id), client.call(Api.Sessions, SessionsParams("default")).sessions.map { it.id })
                assertTrue(client.call(Api.Sessions, SessionsParams("other")).sessions.isEmpty())
                client.call(Api.DeleteAgent, AgentRef("other"))
                assertEquals(2, client.call(Api.RoomSession, RoomSessionRef(room.id, session.id)).session.messages.size)
            }
        } finally { client.close(); server.stop() }
        val restarted = harness()
        try {
            assertEquals(listOf(room), restarted.store.rooms())
            assertEquals(2, restarted.store.roomSessions(room.id).single().messages.size)
        } finally { restarted.close() }
    }
}
