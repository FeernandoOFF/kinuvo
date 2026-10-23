package kinuvo.harness.core.store

import kinuvo.harness.core.TestHome
import kinuvo.protocol.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class RoomsStoreTest {
    @Test fun additiveStorageSurvivesRestartAndAgentDeletion() = runTest {
        val home = TestHome()
        val agent = home.store.createAgent("other")
        val direct = home.store.createSession(home.agent, "legacy instructions")
        home.store.append(home.agent, direct.id, SessionEntry.UserMessage("legacy message"))
        val room = Room("team", "Team", listOf(home.agent.id, agent.id), home.agent.id)
        home.store.saveRoom(room)
        val message = RoomMessage("message", agent.id, agent.name, "public reply", rootId = "root")
        val settings = SessionSettings(exec = "ssh", workspace = listOf("/remote"), ssh = SshSettings("host", "user"))
        home.store.append(home.agent, direct.id, SessionEntry.Settings(settings))
        val privateSession = home.store.createSession(home.agent, "private", roomExecution = true)
        val session = RoomSession("room-session", room.id, listOf(message), title = "Release", memberSettings = mapOf(agent.id to settings),
            memberExecutions = mapOf(home.agent.id to RoomExecution(privateSession.id, cursor = 1, interrupted = true)))
        home.store.saveRoomSession(session)
        home.store.append(home.agent, privateSession.id, SessionEntry.UserMessage("private request"))
        assertFalse(home.store.session(home.agent, privateSession.id).inChats)
        val reopened = FileStore(home.files)
        assertEquals(listOf(room), reopened.rooms())
        assertEquals(listOf(session), reopened.roomSessions(room.id))
        assertEquals(settings, reopened.session(home.agent, direct.id).settings)
        assertEquals(direct.id, reopened.sessions(home.agent).single { it.inChats }.id)
        assertEquals("legacy message", reopened.session(home.agent, direct.id).title)
        reopened.deleteAgent(agent)
        assertEquals(listOf(session), FileStore(home.files).roomSessions(room.id))
        assertEquals(settings, FileStore(home.files).roomSessions(room.id).single().memberSettings[agent.id])
        reopened.append(home.agent, direct.id, SessionEntry.Settings(SessionSettings()))
        assertEquals(SessionSettings(), FileStore(home.files).session(home.agent, direct.id).settings)
        assertEquals("legacy message", FileStore(home.files).session(home.agent, direct.id).title)
        assertFailsWith<IllegalArgumentException> { reopened.roomSessions("../agents") }
    }
}
