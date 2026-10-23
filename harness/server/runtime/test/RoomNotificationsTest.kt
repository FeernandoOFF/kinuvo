package kinuvo.harness.server

import ai.koog.prompt.streaming.StreamFrame
import kinuvo.harness.core.*
import kinuvo.harness.core.routines.RoutineRun
import kinuvo.harness.testing.*
import kinuvo.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class RoomNotificationsTest {
    @Test fun privateCommentaryAndPassNeverNotify() = runTest { roomCompletion("pass_turn") }
    @Test fun publicReplyNeverFallsBackToPrivateCommentary() = runTest { roomCompletion("public_reply") }
    @Test fun privateRoomFailuresNeverNotify() = runTest { roomCompletion("error") }

    private suspend fun TestScope.roomCompletion(outcome: String) {
        val sent = mutableListOf<Notification>()
        val fixture = RuntimeFixture().apply {
            members += agent.copy(id = "pip")
            notifier = Notifier { sent += it }
        }
        val harness = fixture.runtime(scriptedModel { _, tools ->
            if (outcome == "error") flow { emit(StreamFrame.TextDelta("PRIVATE_COMMENTARY")); error("PRIVATE_FAILURE") }
            else flowOf(StreamFrame.TextDelta("PRIVATE_COMMENTARY"),
                StreamFrame.ToolCallComplete("finish", tools.single { it.name.contains(outcome) }.name,
                    if (outcome == "public_reply") """{"text":"Accepted public answer"}""" else "{}"), StreamFrame.End())
        })
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val runs = Runs(harness.log, scope)
        val rooms = Rooms(harness, runs, scope)
        val notifications = Notifications(harness, scope, runs::routine)
        harness.log.onEvent(notifications::on)
        try {
            val room = rooms.create(CreateRoomParams("Peers", listOf("test", "pip")))
            val session = rooms.createSession(room.id)
            rooms.send(SendRoomParams(room.id, session.id, "Work", listOf("test")))
            advanceUntilIdle()
            val view = rooms.view(RoomSessionRef(room.id, session.id))
            if (outcome == "error") assertTrue(assertNotNull(view.error).contains("PRIVATE_FAILURE")) else assertNull(view.error)
            val private = view.session.memberExecutions.getValue("test").sessionId
            assertTrue(fixture.store.entries(fixture.agent, private).filterIsInstance<SessionEntry.AssistantMessage>().single().text.startsWith("PRIVATE_COMMENTARY"))
            assertEquals(if (outcome == "public_reply") listOf("Work", "Accepted public answer") else listOf("Work"), view.session.messages.map { it.text })
            assertEquals(emptyList(), sent)
        } finally { rooms.stopAll(); scope.cancel(); harness.close() }
    }

    @Test fun privateRoomApprovalsAndQuestionsStillNotifyWithOriginalIds() = runTest {
        val sent = mutableListOf<Notification>()
        val fixture = RuntimeFixture().apply {
            members = listOf(agent.copy(config = agent.config.copy(approvals = "strict", tools = listOf("ask_user"))), agent.copy(id = "pip"))
            notifier = Notifier { sent += it }
        }
        val harness = fixture.runtime(scriptedModel { prompt, tools ->
            if (prompt.messages.toolResults.isEmpty()) roomCall(tools, "ask_user", """{"title":"Choose publicly safe input","questions":[{"id":"choice","label":"Which option?","kind":"text"}]}""")
            else roomCall(tools, "pass_turn")
        })
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val runs = Runs(harness.log, scope)
        val rooms = Rooms(harness, runs, scope)
        harness.log.onEvent(Notifications(harness, scope, runs::routine)::on)
        try {
            val room = rooms.create(CreateRoomParams("Peers", listOf("test", "pip")))
            val session = rooms.createSession(room.id)
            val ref = RoomSessionRef(room.id, session.id)
            rooms.send(SendRoomParams(room.id, session.id, "Work", listOf("test")))
            runCurrent()
            val first = assertNotNull(rooms.view(ref).progress)
            val questions = assertNotNull(first.live.questions)
            assertEquals(first.sessionId, sent.single().sessionId)
            assertTrue(sent.single().urgent)
            assertEquals("Choose publicly safe input", sent.last().body)
            assertTrue(harness.questions.respond(questions.questionsId, mapOf("choice" to listOf("PRIVATE_FORM_ANSWER"))))
            runCurrent()
            val pass = assertNotNull(rooms.view(ref).progress?.live?.approval)
            assertEquals(first.sessionId, sent.last().sessionId)
            assertTrue(sent.last().title.contains("Approval"))
            assertTrue(harness.approvals.respond(pass.approvalId, ApprovalDecision.AllowOnce))
            advanceUntilIdle()
            assertNull(rooms.view(ref).error)
            assertEquals(2, sent.size)
            assertTrue(sent.all { it.urgent && "PRIVATE_FORM_ANSWER" !in it.body })
        } finally { rooms.stopAll(); scope.cancel(); harness.close() }
    }

    @Test fun directRepliesAndRoutineReportsKeepTheirNotificationBehavior() = runTest {
        val sent = mutableListOf<Notification>()
        val fixture = RuntimeFixture().apply { notifier = Notifier { sent += it } }
        val harness = fixture.runtime(scriptedModel { _, _ -> flowOf(StreamFrame.TextDelta("Direct reply"), StreamFrame.End()) })
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val runs = Runs(harness.log, scope)
        val notifications = Notifications(harness, scope, runs::routine)
        harness.log.onEvent(notifications::on)
        try {
            val agent = fixture.agent
            val session = harness.newSession(agent).id
            runs.start(agent, session, "Hi") { harness.runner(agent, session) }
            advanceUntilIdle()
            assertEquals(listOf("Direct reply"), sent.map { it.body })
            for (report in listOf(false, true)) {
                val routine = Routine("watch", agent.id, "Watch", "Check", Trigger.Every(30), "UTC")
                val run = RoutineRun(routine) { outcome ->
                    notifications.routineRan(RoutineRan(agent.id, RoutineRef("watch", "Watch"), RunStatus.Ok, session, summary = outcome.summary))
                }
                run.decide("Routine report")
                if (!report) run.outcome = SessionEntry.Outcome(false, "")
                runs.start(agent, session, "Check", routine = run.ref) { harness.runner(agent, session, routine = run) }
                advanceUntilIdle()
                assertEquals(if (report) listOf("Direct reply", "Routine report") else listOf("Direct reply"), sent.map { it.body })
            }
            harness.log.publish(agent, session, AgentEvent.RunError("error", "Direct error"))
            runCurrent()
            assertEquals("Direct error", sent.last().body)
        } finally { runs.stopAll(); scope.cancel(); harness.close() }
    }
}
