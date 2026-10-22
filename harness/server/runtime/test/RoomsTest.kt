package kinuvo.harness.server

import ai.koog.prompt.streaming.StreamFrame
import kinuvo.harness.testing.*
import kinuvo.harness.core.*
import kinuvo.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flow
import ai.koog.agents.core.tools.ToolDescriptor
import kinuvo.harness.core.tools.Tool
import kotlinx.serialization.json.JsonObject
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class RoomsTest {
    @Test fun unmentionedPeersParticipateSequentiallyAndCanPassWithoutPublicText() = runTest {
        val fixture = RuntimeFixture()
        fixture.members += fixture.agent.copy(id = "other")
        val order = mutableListOf<String>()
        val harness = fixture.runtime(scriptedModel { prompt, tools ->
            val context = prompt.messages.joinToString()
            if ("You are test." in context) {
                order += "test"
                flowOf(StreamFrame.TextDelta("Useful reply; @other is only prose"), StreamFrame.End())
            } else if (prompt.messages.toolResults.isEmpty()) {
                order += "other"
                val pass = tools.single { it.name.contains("pass_turn") }
                flowOf(StreamFrame.ToolCallComplete("pass", pass.name, "{}"), StreamFrame.End())
            } else flowOf(StreamFrame.TextDelta("This passed reply stays private"), StreamFrame.End())
        })
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val rooms = Rooms(harness, Runs(harness.log, scope), scope)
        try {
            val room = rooms.create(CreateRoomParams("Team", listOf("test", "other")))
            val session = rooms.createSession(room.id)
            rooms.send(SendRoomParams(room.id, session.id, "Discuss"))
            advanceUntilIdle()
            val view = rooms.view(RoomSessionRef(room.id, session.id))
            assertEquals(listOf("test", "other"), order)
            assertNull(view.error)
            assertEquals(listOf(null, "test"), view.session.messages.map { it.sender })
            assertEquals("Discuss", view.session.title)
            assertFalse(view.session.messages.joinToString().contains("stays private"))
            order.clear()
            rooms.send(SendRoomParams(room.id, session.id, "Only @test in prose", listOf("other")))
            advanceUntilIdle()
            assertEquals(listOf("other"), order)
        } finally { rooms.stopAll(); scope.cancel(); harness.close() }
    }

    @Test fun memberSettingsAndActiveSnapshotAreScopedToOneTitledSession() = runTest {
        val fixture = RuntimeFixture()
        fixture.execOptions = setOf("host")
        fixture.members += fixture.agent.copy(id = "other")
        val harness = fixture.runtime(scriptedModel { _, _ -> flow { awaitCancellation() } })
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val rooms = Rooms(harness, Runs(harness.log, scope), scope)
        try {
            val room = rooms.create(CreateRoomParams("Team", listOf("test", "other")))
            val session = rooms.createSession(room.id, "Release")
            val second = rooms.createSession(room.id, "Research")
            val ref = RoomSessionRef(room.id, session.id)
            val settings = SessionSettings(exec = "host", workspace = listOf("/work"))
            rooms.configure(ConfigureRoomSessionParams(room.id, session.id, "other", settings))
            rooms.send(SendRoomParams(room.id, session.id, "work", listOf("other")))
            runCurrent()
            rooms.configure(ConfigureRoomSessionParams(room.id, session.id, "other", SessionSettings()))
            val view = rooms.view(ref)
            assertEquals("host", view.progress?.effective?.exec)
            assertEquals("host", view.members["other"]?.active?.exec)
            assertEquals("none", view.members["other"]?.effective?.exec)
            assertEquals("none", view.members["test"]?.effective?.exec)
            assertTrue(view.session.memberSettings.isEmpty())
            assertEquals("none", rooms.view(RoomSessionRef(room.id, second.id)).members["other"]?.effective?.exec)
            assertEquals("Renamed", rooms.update(UpdateRoomSessionParams(room.id, session.id, "Renamed")).title)
            assertEquals(setOf("Renamed", "Research"), rooms.sessions(room.id).map { it.title }.toSet())
            rooms.stop(ref)
            advanceUntilIdle()
            assertNull(rooms.view(ref).members["other"]?.active)
        } finally { rooms.stopAll(); scope.cancel(); harness.close() }
    }

    @Test fun artifactsKeepPublicAndPrivateSessionAttribution() = runTest {
        val fixture = RuntimeFixture()
        fixture.members = listOf(fixture.agent.copy(config = fixture.agent.config.copy(tools = listOf("artifact"))), fixture.agent.copy(id = "other"))
        val harness = fixture.runtime(scriptedModel { prompt, tools ->
            if (prompt.messages.toolResults.isEmpty()) {
                val tool = tools.single { it.description.startsWith("Make something") }
                flowOf(StreamFrame.ToolCallComplete("artifact", tool.name, """{"action":"create","title":"Result","type":"txt","content":"Public result"}"""), StreamFrame.End())
            } else flowOf(StreamFrame.TextDelta("Result ready"), StreamFrame.End())
        })
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val rooms = Rooms(harness, Runs(harness.log, scope), scope)
        try {
            val room = rooms.create(CreateRoomParams("Team", listOf("test", "other")))
            val session = rooms.createSession(room.id)
            rooms.send(SendRoomParams(room.id, session.id, "work", listOf("test")))
            advanceUntilIdle()
            val artifact = harness.artifacts.list().single()
            assertEquals("test", artifact.agentId)
            assertEquals(session.id, artifact.roomSessionId)
            assertNotEquals(session.id, artifact.sessionId)
            val privateId = assertNotNull(artifact.sessionId)
            assertTrue(fixture.transcripts.entries(fixture.agent, privateId).filterIsInstance<SessionEntry.Started>().single().roomExecution)
            assertNull(rooms.view(RoomSessionRef(room.id, session.id)).error)
        } finally { rooms.stopAll(); scope.cancel(); harness.close() }
    }

    @Test fun immediateStopAndShutdownReleaseTurnBeforeFirstDispatch() = runTest {
        val fixture = RuntimeFixture()
        fixture.members += fixture.agent.copy(id = "other")
        val harness = fixture.runtime(scriptedModel { _, _ -> flowOf(StreamFrame.TextDelta("done"), StreamFrame.End()) })
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val runs = Runs(harness.log, scope)
        val rooms = Rooms(harness, runs, scope)
        try {
            val room = rooms.create(CreateRoomParams("Team", listOf("test", "other"), "test"))
            val session = rooms.createSession(room.id)
            val ref = RoomSessionRef(room.id, session.id)
            rooms.send(SendRoomParams(room.id, session.id, "first"))
            rooms.stop(ref)
            runCurrent()
            assertFalse(rooms.view(ref).running)
            assertTrue(runs.sessions().isEmpty())
            rooms.send(SendRoomParams(room.id, session.id, "second"))
            advanceUntilIdle()
            assertEquals("done", rooms.view(ref).session.messages.last().text)
            rooms.send(SendRoomParams(room.id, session.id, "third"))
            rooms.stopAll()
            assertFalse(rooms.view(ref).running)
            assertTrue(runs.sessions().isEmpty())
            rooms.send(SendRoomParams(room.id, session.id, "shutdown"))
            scope.cancel()
            rooms.stopAll()
            assertFalse(rooms.view(ref).running)
        } finally { rooms.stopAll(); scope.cancel(); harness.close() }
    }

    @Test fun peerFollowupSharesOnlyPublicTextWithoutAnotherCoordinatorRun() = runTest {
        val fixture = RuntimeFixture()
        fixture.members = listOf(fixture.agent.copy(config = fixture.agent.config.copy(approvals = "full", tools = listOf("web_fetch"))), fixture.agent.copy(id = "other"))
        var workerPrompt = ""
        var calls = 0
        val model = scriptedModel { prompt, tools ->
            val context = prompt.messages.joinToString()
            if ("You are other." in context) {
                workerPrompt = context
                flowOf(StreamFrame.TextDelta("PUBLIC_WORKER_RESULT"), StreamFrame.End())
            } else if (prompt.messages.toolResults.isEmpty()) {
                val tool = tools.single { it.description == "Private lookup" }
                flowOf(StreamFrame.ToolCallComplete("private", tool.name, "{}"), StreamFrame.End())
            } else if (prompt.messages.toolResults.size == 1) {
                assertTrue("PRIVATE_TOOL_RESULT" in context)
                calls++
                val tool = tools.single { it.description.contains("Queue a public") }
                flowOf(StreamFrame.ToolCallComplete("delegate", tool.name, """{"agentId":"other","text":"PUBLIC_REQUEST"}"""), StreamFrame.End())
            } else flowOf(StreamFrame.TextDelta("PUBLIC_COORDINATOR_REPLY"), StreamFrame.End())
        }
        fixture.fetch = { object : Tool {
            override val descriptor = ToolDescriptor("web_fetch", "Private lookup")
            override suspend fun execute(args: JsonObject) = "PRIVATE_TOOL_RESULT"
        } }
        fixture.documents[AgentDoc.Soul] = "PRIVATE_COORDINATOR_SOUL"
        fixture.agentDocuments["other" to AgentDoc.Soul] = "WORKER_OWN_INSTRUCTIONS"
        val harness = fixture.runtime(model)
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val rooms = Rooms(harness, Runs(harness.log, scope), scope)
        try {
            val room = rooms.create(CreateRoomParams("Team", listOf("test", "other"), "test"))
            val session = rooms.createSession(room.id)
            rooms.send(SendRoomParams(room.id, session.id, "work", listOf("test")))
            advanceUntilIdle()
            val view = rooms.view(RoomSessionRef(room.id, session.id))
            assertNull(view.error)
            assertEquals(1, calls)
            assertTrue("PUBLIC_REQUEST" in workerPrompt)
            assertTrue("WORKER_OWN_INSTRUCTIONS" in workerPrompt)
            assertFalse("PRIVATE_COORDINATOR_SOUL" in workerPrompt)
            assertFalse("PRIVATE_TOOL_RESULT" in workerPrompt)
            assertEquals("other", view.session.messages.last().sender)
            assertEquals("PUBLIC_WORKER_RESULT", view.session.messages.last().text)
            assertEquals(2, fixture.transcripts.sessions(fixture.agent).size)
        } finally { rooms.stopAll(); scope.cancel(); harness.close() }
    }

    @Test fun stopDuringRunnerPreparationReleasesReservation() = runTest {
        val preparing = CompletableDeferred<Unit>()
        val plugin = object : HarnessPlugin {
            override val definition = PluginDefinition("example.preparing", "Preparing")
            override suspend fun prepare(run: RunContext, activate: Boolean): PluginTools {
                if (activate) { preparing.complete(Unit); awaitCancellation() }
                return PluginTools(emptyList())
            }
        }
        val fixture = RuntimeFixture(listOf(plugin))
        fixture.members += fixture.agent.copy(id = "other")
        val harness = fixture.runtime(scriptedModel { _, _ -> flowOf(StreamFrame.End()) })
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val runs = Runs(harness.log, scope)
        val rooms = Rooms(harness, runs, scope)
        try {
            val room = rooms.create(CreateRoomParams("Team", listOf("test", "other"), "test"))
            val session = rooms.createSession(room.id)
            val ref = RoomSessionRef(room.id, session.id)
            rooms.send(SendRoomParams(room.id, session.id, "work", listOf("test")))
            runCurrent()
            assertTrue(preparing.isCompleted)
            assertTrue(runs.sessions().isNotEmpty())
            rooms.stop(ref)
            advanceUntilIdle()
            assertFalse(rooms.view(ref).running)
            assertTrue(runs.sessions().isEmpty())
        } finally { rooms.stopAll(); scope.cancel(); harness.close() }
    }

    @Test fun approvalResolutionAndTextStayOrderedOnDefaultDispatcher() = runBlocking {
        val resumed = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val fixture = RuntimeFixture()
        fixture.members += fixture.agent.copy(id = "other")
        val harness = fixture.runtime(scriptedModel { prompt, tools ->
            if (prompt.messages.toolResults.isEmpty()) {
                val tool = tools.single { it.description.contains("Queue a public") }
                flowOf(StreamFrame.ToolCallComplete("delegate", tool.name, """{"agentId":"other","text":"public"}"""), StreamFrame.End())
            } else flow {
                repeat(100) { emit(StreamFrame.TextDelta("$it,")) }
                resumed.complete(Unit)
                finish.await()
                emit(StreamFrame.End())
            }
        })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val runs = Runs(harness.log, scope)
        val rooms = Rooms(harness, runs, scope)
        try {
            val room = rooms.create(CreateRoomParams("Team", listOf("test", "other"), "test"))
            val session = rooms.createSession(room.id)
            val ref = RoomSessionRef(room.id, session.id)
            rooms.send(SendRoomParams(room.id, session.id, "work", listOf("other")))
            val approval = withTimeout(5000) {
                var approval: AgentEvent.ApprovalRequested? = null
                while (approval == null) { approval = rooms.view(ref).progress?.live?.approval; yield() }
                approval
            }
            harness.approvals.respond(approval.approvalId, ApprovalDecision.Deny())
            withTimeout(5000) { resumed.await() }
            val expected = (0 until 100).joinToString("") { "$it," }
            val live = withTimeout(5000) {
                while (rooms.view(ref).progress!!.live.text.length < expected.length) yield()
                rooms.view(ref).progress!!.live
            }
            assertNull(live.approval)
            assertEquals(expected, live.text)
            rooms.stop(ref)
            withTimeout(5000) { rooms.stopAll() }
            assertTrue(runs.sessions().isEmpty())
            assertEquals(0, scope.coroutineContext.job.children.count())
        } finally { finish.complete(Unit); rooms.stopAll(); scope.cancel(); harness.close() }
    }

    @Test fun denyRuleBlocksRoomCollaborationEvenAtFullTolerance() = runTest {
        val fixture = RuntimeFixture()
        fixture.members = listOf(fixture.agent.copy(config = fixture.agent.config.copy(approvals = "full")), fixture.agent.copy(id = "other"))
        val identity = ToolIdentity(PluginInstanceId("kinuvo.rooms"), "address_member")
        fixture.rules = TrustRules(deny = DenyRules(tools = listOf(identity.key)))
        val harness = fixture.runtime(scriptedModel { prompt, tools ->
            if (prompt.messages.toolResults.isEmpty()) {
                val tool = tools.single { it.description.contains("Queue a public") }
                flowOf(StreamFrame.ToolCallComplete("delegate", tool.name, """{"agentId":"other","text":"public"}"""), StreamFrame.End())
            } else flowOf(StreamFrame.TextDelta("Denied"), StreamFrame.End())
        })
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val rooms = Rooms(harness, Runs(harness.log, scope), scope)
        try {
            val room = rooms.create(CreateRoomParams("Team", listOf("test", "other"), "test"))
            val session = rooms.createSession(room.id)
            rooms.send(SendRoomParams(room.id, session.id, "work", listOf("test")))
            advanceUntilIdle()
            val view = rooms.view(RoomSessionRef(room.id, session.id))
            assertNull(view.error)
            assertEquals(listOf(null, "test"), view.session.messages.map { it.sender })
            assertEquals(1, fixture.transcripts.sessions(fixture.agent).size)
            val private = fixture.transcripts.sessions(fixture.members.first()).single()
            assertTrue(fixture.transcripts.entries(fixture.members.first(), private.id).filterIsInstance<SessionEntry.ToolResult>().single().isError)
        } finally { rooms.stopAll(); scope.cancel(); harness.close() }
    }

    @Test fun validatesMembershipAndKeepsDirectIds() = runTest {
        val fixture = RuntimeFixture()
        fixture.members += fixture.agent.copy(id = "other")
        val harness = fixture.runtime(scriptedModel { _, _ -> flowOf(StreamFrame.End()) })
        val rooms = Rooms(harness, Runs(harness.log, backgroundScope), backgroundScope)
        assertEquals("test", rooms.list().first { it.direct }.id)
        assertFailsWith<IllegalArgumentException> { rooms.create(CreateRoomParams("Team", listOf("test", "missing"), "test")) }
        assertEquals("", rooms.create(CreateRoomParams("Legacy", listOf("test", "other"), "missing")).coordinator)
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
            if (tools.none { it.description.contains("Queue a public") }) flowOf(StreamFrame.TextDelta("Final synthesis"), StreamFrame.End())
            else if (prompt.messages.toolResults.isEmpty()) {
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
            rooms.send(SendRoomParams(room.id, session.id, "work", listOf("test")))
            advanceUntilIdle()
            val view = rooms.view(RoomSessionRef(room.id, session.id))
            assertFalse(view.running)
            assertEquals(Rooms.MAX_DISPATCHES, count)
            assertTrue(assertNotNull(view.error).contains("participation limit"))
            assertEquals("public reply", view.session.messages.last().text)
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
            rooms.send(SendRoomParams(room.id, session.id, "work", listOf("test")))
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
