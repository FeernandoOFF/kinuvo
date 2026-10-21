package kinuvo.harness.server

import ai.koog.prompt.streaming.StreamFrame
import kinuvo.harness.testing.*
import kinuvo.harness.core.*
import kinuvo.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class RoomsTest {
    @Test fun validatesMembershipAndKeepsDirectIds() = runTest {
        val fixture = RuntimeFixture()
        fixture.members += fixture.agent.copy(id = "other")
        val harness = fixture.runtime(scriptedModel { _, _ -> flowOf(StreamFrame.End()) })
        val rooms = Rooms(harness, Runs(harness.log, backgroundScope), backgroundScope)
        assertEquals("test", rooms.list().first { it.direct }.id)
        assertFailsWith<IllegalArgumentException> { rooms.create(CreateRoomParams("Team", listOf("test", "missing"), "test")) }
        assertFailsWith<IllegalArgumentException> { rooms.create(CreateRoomParams("Team", listOf("test", "other"), "missing")) }
        val room = rooms.create(CreateRoomParams("Team", listOf("test", "other"), "test"))
        val session = rooms.createSession(room.id)
        assertFailsWith<IllegalArgumentException> { rooms.send(SendRoomParams(room.id, session.id, "hello", listOf("missing"))) }
        assertTrue(rooms.view(RoomSessionRef(room.id, session.id)).session.messages.isEmpty())
        harness.close()
    }

    @Test fun collaborationIsBoundedAndPrivateContextDoesNotLeak() = runTest {
        val fixture = RuntimeFixture()
        fixture.members = listOf(fixture.agent.copy(config = fixture.agent.config.copy(approvals = "full")), fixture.agent.copy(id = "other", config = fixture.agent.config.copy(approvals = "full")))
        fixture.documents[AgentDoc.Soul] = "PRIVATE_SOUL"
        fixture.documents[AgentDoc.Memory] = "PRIVATE_MEMORY"
        var count = 0
        val model = scriptedModel { prompt, tools ->
            val context = prompt.messages.joinToString { it.toString() }
            val target = if ("You are test." in context) "other" else "test"
            if (prompt.messages.toolResults.isEmpty()) {
                count++
                val tool = tools.single { it.name.contains("address_member") || it.description.contains("Queue a public") }
                flowOf(StreamFrame.ToolCallComplete("delegate-$count", tool.name, """{"agentId":"$target","text":"public request"}"""), StreamFrame.End())
            } else flowOf(StreamFrame.TextDelta("public reply"), StreamFrame.End())
        }
        val harness = fixture.runtime(model)
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val rooms = Rooms(harness, Runs(harness.log, scope), scope)
        try {
            val room = rooms.create(CreateRoomParams("Team", listOf("test", "other"), "test"))
            val session = rooms.createSession(room.id)
            rooms.send(SendRoomParams(room.id, session.id, "work"))
            advanceUntilIdle()
            val view = rooms.view(RoomSessionRef(room.id, session.id))
            assertFalse(view.running)
            assertEquals(Rooms.MAX_DISPATCHES, count)
            assertTrue(view.session.messages.any { it.sender == "other" })
            val public = view.session.messages.joinToString()
            assertFalse("PRIVATE_SOUL" in public)
            assertFalse("PRIVATE_MEMORY" in public)
            assertTrue(view.session.messages.filter { it.sender != null }.all { it.rootId == view.session.messages.first().id })
        } finally { rooms.stopAll(); scope.cancel(); harness.close() }
    }

    @Test fun memberFailureStopsTheTurnAndRemainsVisible() = runTest {
        val fixture = RuntimeFixture()
        fixture.members += fixture.agent.copy(id = "other")
        val harness = fixture.runtime(scriptedModel { _, _ -> error("Model unavailable") })
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val runs = Runs(harness.log, scope)
        val rooms = Rooms(harness, runs, scope)
        try {
            val room = rooms.create(CreateRoomParams("Team", listOf("test", "other"), "test"))
            val session = rooms.createSession(room.id)
            rooms.send(SendRoomParams(room.id, session.id, "work", listOf("test", "other")))
            advanceUntilIdle()
            val view = rooms.view(RoomSessionRef(room.id, session.id))
            assertFalse(view.running)
            assertTrue(assertNotNull(view.error).contains("Model unavailable"))
            assertEquals(1, fixture.transcripts.sessions(fixture.agent).size)
            assertEquals(1, view.session.messages.size)
            assertTrue(runs.sessions().isEmpty())
        } finally { rooms.stopAll(); scope.cancel(); harness.close() }
    }

    @Test fun explicitRecipientsAndFormsUsePrivateExecutionSessions() = runTest {
        val fixture = RuntimeFixture()
        fixture.members = listOf(fixture.agent, fixture.agent.copy(id = "other", config = fixture.agent.config.copy(tools = listOf("ask_user"))))
        val harness = fixture.runtime(scriptedModel { prompt, tools ->
            if (prompt.messages.toolResults.isEmpty()) {
                val tool = tools.single { it.description.startsWith("Ask the user") }
                flowOf(StreamFrame.ToolCallComplete("form", tool.name, """{"questions":[{"id":"choice","label":"Which option?","kind":"text"}]}"""), StreamFrame.End())
            } else flowOf(StreamFrame.TextDelta("Public answer"), StreamFrame.End())
        })
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val runs = Runs(harness.log, scope)
        val rooms = Rooms(harness, runs, scope)
        try {
            val room = rooms.create(CreateRoomParams("Team", listOf("test", "other"), "test"))
            val session = rooms.createSession(room.id)
            val ref = RoomSessionRef(room.id, session.id)
            rooms.send(SendRoomParams(room.id, session.id, "Please check", listOf("other")))
            runCurrent()
            val progress = rooms.view(ref).progress!!
            assertEquals("other", progress.agentId)
            val asked = assertNotNull(progress.live.questions)
            assertTrue(harness.questions.respond(asked.questionsId, mapOf("choice" to listOf("private form answer"))))
            advanceUntilIdle()
            val public = rooms.view(ref).session.messages
            assertEquals(listOf(null, "other"), public.map { it.sender })
            assertFalse(public.joinToString().contains("private form answer"))
            val private = fixture.transcripts.entries(fixture.members.last(), progress.sessionId)
            assertTrue(private.filterIsInstance<SessionEntry.Started>().single().roomExecution)
            assertTrue(private.filterIsInstance<SessionEntry.ToolResult>().single().output.contains("private form answer"))
            assertFailsWith<IllegalArgumentException> { harness.reflect(fixture.members.last(), progress.sessionId, private) }
        } finally { rooms.stopAll(); scope.cancel(); harness.close() }
    }

    @Test fun stopPreventsQueuedChildrenAndApprovalUsesOriginalBroker() = runTest {
        val fixture = RuntimeFixture()
        fixture.members += fixture.agent.copy(id = "other")
        val harness = fixture.runtime(scriptedModel { _, tools ->
            val tool = tools.single { it.description.contains("Queue a public") }
            flowOf(StreamFrame.ToolCallComplete("delegate", tool.name, """{"agentId":"other","text":"public"}"""), StreamFrame.End())
        })
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val runs = Runs(harness.log, scope)
        val rooms = Rooms(harness, runs, scope)
        try {
            val room = rooms.create(CreateRoomParams("Team", listOf("test", "other"), "test"))
            val session = rooms.createSession(room.id)
            val ref = RoomSessionRef(room.id, session.id)
            rooms.send(SendRoomParams(room.id, session.id, "work"))
            runCurrent()
            val pending = rooms.view(ref).progress!!
            assertTrue(runs.isActive(pending.sessionId))
            assertNotNull(pending.live.approval)
            assertTrue(harness.approvals.respond(pending.live.approval!!.approvalId, ApprovalDecision.Deny()))
            runCurrent()
            assertEquals(1, rooms.view(ref).session.messages.size)
            rooms.send(SendRoomParams(room.id, session.id, "try again"))
            runCurrent()
            rooms.stop(ref)
            advanceUntilIdle()
            assertFalse(rooms.view(ref).running)
            assertTrue(runs.sessions().isEmpty())
            assertEquals(2, rooms.view(ref).session.messages.size)
        } finally { rooms.stopAll(); scope.cancel(); harness.close() }
    }
}
