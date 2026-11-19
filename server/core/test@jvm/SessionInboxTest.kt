package kinuvo.server

import kinuvo.protocol.Api
import kinuvo.protocol.agent.AgentId
import kinuvo.protocol.agent.AgentRef
import kinuvo.protocol.capability.ServerFeature
import kinuvo.protocol.session.SendParams
import kinuvo.protocol.session.SessionStatus
import kinuvo.protocol.session.SessionsParams
import kinuvo.protocol.session.UpdateSessionParams
import kinuvo.testing.server.TestServer
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SessionInboxTest {
    @Test fun inboxChangesReachAnotherDevice() = runBlocking {
        val agent = AgentId("inbox")
        TestServer.start(script = { reply("Finished") }, scenario = { agent("inbox") }).use { server ->
            withTimeout(15_000) {
                val phone = server.client("phone", server.pairDevice("phone")).also { it.connect() }
                val desktop = server.client("desktop", server.pairDevice("desktop")).also { it.connect() }
                assertTrue(ServerFeature.SessionInbox in requireNotNull(phone.lastConnect).capabilities.features)
                val id = desktop.call(Api.CreateSession, AgentRef(agent)).id
                val changes = phone.events(Api.SessionChanged)

                val settled = async(start = CoroutineStart.UNDISPATCHED) { changes.first { it.id == id && it.settled } }
                desktop.call(Api.UpdateSession, UpdateSessionParams(agent, id, settled = true))
                settled.await()
                assertTrue(phone.call(Api.Sessions, SessionsParams(agent)).sessions.single().settled)

                val started = async(start = CoroutineStart.UNDISPATCHED) { changes.first { it.id == id && it.status == SessionStatus.Running } }
                val unread = async(start = CoroutineStart.UNDISPATCHED) { changes.first { it.id == id && it.unread } }
                desktop.call(Api.Send, SendParams(agent, id, "Start"))
                assertFalse(started.await().settled)
                assertTrue(unread.await().unread)
                assertTrue(phone.call(Api.Sessions, SessionsParams(agent)).sessions.single().unread)

                val read = async(start = CoroutineStart.UNDISPATCHED) {
                    desktop.events(Api.SessionChanged).first { it.id == id && !it.unread }
                }
                phone.call(Api.UpdateSession, UpdateSessionParams(agent, id, seen = true))
                assertFalse(read.await().unread)
                assertFalse(desktop.call(Api.Sessions, SessionsParams(agent)).sessions.single().unread)
            }
        }
    }
}
