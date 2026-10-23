package kinuvo.harness.server

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import kinuvo.harness.core.*
import kinuvo.harness.core.tools.Tool
import kinuvo.harness.core.tools.ToolRun
import kinuvo.harness.testing.*
import kinuvo.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*

internal fun roomCall(tools: List<ToolDescriptor>, name: String, args: String = "{}", id: String = name) =
    flowOf(StreamFrame.ToolCallComplete(id, tools.single { it.name.contains(name) }.name, args), StreamFrame.End())
internal fun roomReply(tools: List<ToolDescriptor>, text: String) = roomCall(tools, "public_reply", buildJsonObject { put("text", text) }.toString())
internal fun roomResult(tools: List<ToolDescriptor>, ids: List<String>, text: String) = roomCall(tools, "return_result", buildJsonObject {
    put("requestIds", JsonArray(ids.map(::JsonPrimitive))); put("text", text)
}.toString())

@OptIn(ExperimentalCoroutinesApi::class)
class RoomLoopTest {
    private class Case(val fixture: RuntimeFixture, model: AgentModel, private val scope: CoroutineScope, limits: Rooms.Limits = Rooms.Limits()) {
        val harness = fixture.runtime(model)
        val runs = Runs(harness.log, scope)
        val rooms = Rooms(harness, runs, scope, limits)
        lateinit var room: Room
        lateinit var session: RoomSession
        val ref get() = RoomSessionRef(room.id, session.id)
        suspend fun start(recipients: List<String> = emptyList()) {
            room = rooms.create(CreateRoomParams("Peers", fixture.members.map { it.id }))
            session = rooms.createSession(room.id)
            send(recipients)
        }
        suspend fun send(recipients: List<String> = emptyList(), text: String = "Which tools can each of you use?") = rooms.send(SendRoomParams(room.id, session.id, text, recipients))
        suspend fun view() = rooms.view(ref)
        suspend fun close() { rooms.stopAll(); scope.cancel(); harness.close() }
    }
    private fun fixture() = RuntimeFixture().apply { members += agent.copy(id = "pip", name = "Pip") }

    @Test fun mergesPeerWorkIntoOriginalPendingTurnAndPublishesOnlyExplicitOutcomes() = runTest {
        val f = fixture()
        f.members = f.members.map { it.copy(config = it.config.copy(tools = listOf("web_fetch"))) }
        f.documents[AgentDoc.Soul] = "PRIVATE_ATLAS_SOUL"
        f.agentDocuments["pip" to AgentDoc.Soul] = "PIP_OWN_SOUL"
        f.fetch = { object : Tool {
            override val descriptor = ToolDescriptor("lookup", "Private lookup")
            override val risk = Risk.Low
            override suspend fun execute(args: JsonObject) = "PRIVATE_LOOKUP"
        } }
        var atlas = 0
        var pip = 0
        val c = Case(f, scriptedModel { prompt, tools ->
            val context = prompt.messages.joinToString()
            if ("You are test." in context) {
                atlas++
                if (atlas == 1) flowOf(StreamFrame.TextDelta("PRIVATE_COMMENTARY"), StreamFrame.ToolCallComplete("lookup", tools.single { it.description == "Private lookup" }.name, "{}"), StreamFrame.End())
                else {
                    assertTrue("PRIVATE_LOOKUP" in context)
                    assertTrue("pip: pending" in context)
                    assertTrue("test: active" in context)
                    roomCall(tools, "address_member", """{"agentId":"pip","requestId":"tools-pip","text":"My lookup tool reads pages; Pip, please describe your tools"}""")
                }
            } else {
                pip++
                assertTrue("My lookup tool reads pages; Pip, please describe your tools" in context)
                assertTrue("tools-pip" in context)
                assertTrue("Which tools can each of you use?" in context)
                assertFalse("PRIVATE_ATLAS_SOUL" in context)
                assertFalse("PRIVATE_LOOKUP" in context)
                assertFalse("PRIVATE_COMMENTARY" in context)
                roomResult(tools, listOf("tools-pip"), "Pip's useful tools answer")
            }
        }, CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)))
        try {
            c.start()
            advanceUntilIdle()
            val view = c.view()
            assertNull(view.error)
            assertFalse(view.running)
            assertEquals(2, atlas)
            assertEquals(1, pip)
            assertEquals(listOf(null, "test", "pip"), view.session.messages.map { it.sender })
            assertEquals(listOf("My lookup tool reads pages; Pip, please describe your tools", "Pip's useful tools answer"), view.session.messages.drop(1).map { it.text })
            assertTrue(view.session.messages.drop(1).all { it.rootId == view.session.messages.first().id })
            assertEquals(view.session.messages[1].id, view.session.messages[2].replyTo)
            assertEquals(listOf("test"), view.session.messages[2].recipients)
        } finally { c.close() }
    }

    @Test fun passFinishesGraphAndClosesUnexecutedBatchedCallsTruthfully() = runTest {
        val f = fixture()
        var sideEffects = 0
        f.members = f.members.map { it.copy(config = it.config.copy(tools = listOf("web_fetch"))) }
        f.fetch = { object : Tool {
            override val descriptor = ToolDescriptor("side_effect", "Side effect")
            override suspend fun execute(args: JsonObject): String { sideEffects++; return "ran" }
        } }
        var calls = 0
        val c = Case(f, scriptedModel { _, tools ->
            calls++
            flowOf(StreamFrame.TextDelta("PRIVATE"),
                StreamFrame.ToolCallComplete("pass", tools.single { it.name.contains("pass_turn") }.name, "{}"),
                StreamFrame.ToolCallComplete("side", tools.single { it.description == "Side effect" }.name, "{}"), StreamFrame.End())
        }, CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)))
        try {
            c.start(listOf("test")); advanceUntilIdle()
            assertNull(c.view().error)
            assertEquals(1, calls)
            assertEquals(0, sideEffects)
            assertEquals(1, c.view().session.messages.size)
            val private = c.view().session.memberExecutions.getValue("test")
            val entries = f.store.entries(f.agent, private.sessionId)
            assertEquals(2, entries.filterIsInstance<SessionEntry.ToolCall>().size)
            assertEquals(2, entries.filterIsInstance<SessionEntry.ToolResult>().size)
            assertFalse(entries.filterIsInstance<SessionEntry.ToolResult>().first().isError)
            assertTrue(entries.filterIsInstance<SessionEntry.ToolResult>().last().isError)
            assertTrue(entries.filterIsInstance<SessionEntry.ToolResult>().last().output.contains("Not run: Room turn completed"))
        } finally { c.close() }
    }

    @Test fun selectedRecipientCanRequestPeerResultAndResumeOnlyAnIntentionalContinuation() = runTest {
        var atlas = 0
        var pip = 0
        val c = Case(fixture(), scriptedModel { prompt, tools ->
            if ("You are test." in prompt.messages.joinToString()) {
                atlas++
                if (atlas == 1) roomCall(tools, "address_member", """{"agentId":"pip","requestId":"research","text":"Check this","waitForResult":true}""")
                else {
                    assertTrue("Returned requests for your continuation: research" in prompt.messages.joinToString())
                    roomReply(tools, "My continued answer")
                }
            } else { pip++; roomResult(tools, listOf("research"), "Research result") }
        }, CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)))
        try {
            c.start(listOf("test")); advanceUntilIdle()
            assertNull(c.view().error)
            assertEquals(2, atlas); assertEquals(1, pip)
            assertEquals(listOf(null, "test", "pip", "test"), c.view().session.messages.map { it.sender })
            assertEquals(2, c.view().session.memberExecutions.size)
        } finally { c.close() }
    }

    @Test fun duplicateRequestIdentityDoesNotRedeliverOrPublishAndExactBudgetFinishesCleanly() = runTest {
        var atlas = 0
        var pip = 0
        val c = Case(fixture(), scriptedModel { prompt, tools ->
            if ("You are test." in prompt.messages.joinToString()) {
                atlas++
                roomCall(tools, "address_member", """{"agentId":"pip","requestId":"stable","text":"Once only","waitForResult":true}""")
            } else { pip++; roomResult(tools, listOf("stable"), "Once result") }
        }, CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)), Rooms.Limits(dispatches = 3, messages = 2))
        try {
            c.start(listOf("test")); advanceUntilIdle()
            assertNull(c.view().error)
            assertEquals(2, atlas); assertEquals(1, pip)
            assertEquals(3, c.view().session.messages.size)
        } finally { c.close() }
    }

    @Test fun deniedAndInvalidTerminalCallsDoNotFinishAndOrdinaryToolsStillRun() = runTest {
        val f = fixture()
        f.members = f.members.map { it.copy(config = it.config.copy(approvals = "full", tools = listOf("web_fetch"))) }
        f.rules = TrustRules(deny = DenyRules(tools = listOf(ToolIdentity(PluginInstanceId("kinuvo.rooms"), "pass_turn").key)))
        var ordinary = 0
        f.fetch = { object : Tool {
            override val descriptor = ToolDescriptor("read", "Read privately")
            override suspend fun execute(args: JsonObject): String { ordinary++; return "private" }
        } }
        var calls = 0
        val c = Case(f, scriptedModel { prompt, tools ->
            when (++calls) {
                1 -> flowOf(StreamFrame.ToolCallComplete("deny", tools.single { it.name.contains("pass_turn") }.name, "{}"), StreamFrame.ToolCallComplete("read", tools.single { it.description == "Read privately" }.name, "{}"), StreamFrame.End())
                2 -> {
                    assertTrue(prompt.messages.toolResults.any { it.toString().contains("Denied", ignoreCase = true) || it.toString().contains("blocked", ignoreCase = true) })
                    roomCall(tools, "return_result", """{"requestIds":["foreign"],"text":"invalid"}""", "invalid")
                }
                3 -> roomCall(tools, "address_member", """{"agentId":"test","text":"self"}""", "self")
                else -> roomReply(tools, "Intended answer")
            }
        }, CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)))
        try {
            c.start(listOf("test")); advanceUntilIdle()
            assertNull(c.view().error)
            assertEquals(4, calls); assertEquals(1, ordinary)
            assertEquals(listOf("Which tools can each of you use?", "Intended answer"), c.view().session.messages.map { it.text })
            val private = c.view().session.memberExecutions.getValue("test")
            assertEquals(3, f.store.entries(f.agent, private.sessionId).filterIsInstance<SessionEntry.ToolResult>().count { it.isError })
        } finally { c.close() }
    }

    @Test fun wrongRootAndWrongOwnerResultsAreRejectedBeforeValidResultSettles() = runTest {
        val f = fixture().apply { members += agent.copy(id = "third") }
        var pip = 0
        var atlas = 0
        var third = 0
        val c = Case(f, scriptedModel { prompt, tools ->
            val context = prompt.messages.joinToString()
            when {
                "You are test." in context -> {
                    if (++atlas == 1) roomCall(tools, "return_result", """{"requestIds":["foreign"],"text":"unknown"}""", "foreign")
                    else roomCall(tools, "address_member", """{"agentId":"pip","requestId":"request","text":"work"}""")
                }
                "You are third." in context -> {
                    if (++third == 1) roomCall(tools, "return_result", """{"requestIds":["request"],"text":"not mine"}""", "wrong-owner")
                    else roomCall(tools, "pass_turn")
                }
                else -> when (++pip) {
                    1 -> roomCall(tools, "return_result", """{"rootId":"another-root","requestIds":["request"],"text":"wrong root"}""", "wrong-root")
                    else -> roomResult(tools, listOf("request"), "Valid result")
                }
            }
        }, CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)))
        try {
            c.start(listOf("test", "third", "pip")); advanceUntilIdle()
            assertNull(c.view().error)
            assertEquals(2, atlas); assertEquals(2, third); assertEquals(2, pip)
            assertEquals(3, c.view().session.messages.size)
            assertEquals("Valid result", c.view().session.messages.last().text)
        } finally { c.close() }
    }

    @Test fun cyclicNewWorkIsFiniteAndLimitIsReportedOnlyWhenWorkIsRejected() = runTest {
        var calls = 0
        val c = Case(fixture(), scriptedModel { prompt, tools ->
            if (prompt.messages.toolResults.lastOrNull()?.toString()?.contains("limit") == true) roomCall(tools, "pass_turn")
            else {
                val target = if ("You are test." in prompt.messages.joinToString()) "pip" else "test"
                calls++
                roomCall(tools, "address_member", """{"agentId":"$target","requestId":"cycle-$calls","text":"New task $calls","waitForResult":true}""", "cycle-$calls")
            }
        }, CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)), Rooms.Limits(dispatches = 4, perMember = 4, depth = 3))
        try {
            c.start(listOf("test")); advanceUntilIdle()
            assertFalse(c.view().running)
            assertTrue(assertNotNull(c.view().error).contains("participation limit"))
            assertEquals(3, calls)
            assertEquals(listOf("Which tools can each of you use?", "New task 1"), c.view().session.messages.map { it.text })
            assertTrue(c.runs.sessions().isEmpty())
        } finally { c.close() }
    }

    @Test fun publicHistoryCapIncludesAllMemberOutputs() = runTest {
        var calls = 0
        val c = Case(fixture(), scriptedModel { prompt, tools ->
            calls++
            if (prompt.messages.toolResults.lastOrNull()?.toString()?.contains("message limit") == true) roomCall(tools, "pass_turn")
            else roomReply(tools, "Public")
        }, CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)), Rooms.Limits(history = 2))
        try {
            c.start(); advanceUntilIdle()
            assertEquals(2, c.view().session.messages.size)
            assertEquals(3, calls)
            assertFailsWith<IllegalArgumentException> { c.send() }
        } finally { c.close() }
    }

    @Test fun deadlineCancelsChildReleasesRunsAndMarksPrivateWorkInterrupted() = runTest {
        var children = 0
        val c = Case(fixture(), scriptedModel { _, _ -> flow { children++; emit(StreamFrame.TextDelta("never public")); awaitCancellation() } }, CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)), Rooms.Limits(timeoutMillis = 100))
        try {
            c.start(); runCurrent()
            assertEquals("", c.view().progress?.live?.text)
            advanceTimeBy(101); runCurrent()
            assertFalse(c.view().running)
            assertTrue(assertNotNull(c.view().error).contains("deadline"))
            assertTrue(c.runs.sessions().isEmpty())
            assertEquals(1, children)
            assertEquals(1, c.view().session.messages.size)
            assertTrue(c.view().session.memberExecutions.getValue("test").interrupted)
        } finally { c.close() }
    }

    @Test fun privateHistoryAndCursorReuseOnlySameMemberAndRoomSessionNeverDirect() = runTest {
        val f = fixture()
        f.documents[AgentDoc.Memory] = "ATLAS_PRIVATE_MEMORY"
        f.agentDocuments["pip" to AgentDoc.Memory] = "PIP_PRIVATE_MEMORY"
        val direct = f.store.createSession(f.agent, "DIRECT_SECRET")
        f.store.append(f.agent, direct.id, SessionEntry.AssistantMessage("DIRECT_ANSWER"))
        var request = 0
        val prompts = mutableListOf<String>()
        val c = Case(f, scriptedModel { prompt, tools ->
            val context = prompt.messages.joinToString()
            prompts += context
            assertFalse("DIRECT_SECRET" in context || "DIRECT_ANSWER" in context)
            roomReply(tools, "Answer ${++request}")
        }, CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)))
        try {
            c.start(listOf("test")); advanceUntilIdle()
            val first = c.view().session.memberExecutions.getValue("test")
            c.send(listOf("test"), "Second root"); advanceUntilIdle()
            val second = c.view().session.memberExecutions.getValue("test")
            assertEquals(first.sessionId, second.sessionId)
            assertTrue(second.cursor > first.cursor)
            assertTrue("Answer 1" in prompts.last())
            assertTrue("since cursor ${first.cursor}" in prompts.last())
            c.send(listOf("pip"), "Pip root"); advanceUntilIdle()
            val pip = c.view().session.memberExecutions.getValue("pip")
            assertNotEquals(first.sessionId, pip.sessionId)
            assertFalse("ATLAS_PRIVATE_MEMORY" in prompts.last())
            assertTrue("PIP_PRIVATE_MEMORY" in prompts.last())
            val old = c.session
            c.session = c.rooms.createSession(c.room.id)
            c.send(listOf("test"), "Separate root"); advanceUntilIdle()
            assertNotEquals(first.sessionId, c.view().session.memberExecutions.getValue("test").sessionId)
            assertFalse("Answer 1" in prompts.last())
            assertFalse("Pip root" in prompts.last())
            val prior = c.rooms.view(RoomSessionRef(c.room.id, old.id)).session
            assertEquals(6, prior.messages.size)
            assertFailsWith<IllegalArgumentException> { c.harness.reflect(f.agent, first.sessionId, f.store.entries(f.agent, first.sessionId)) }
        } finally { c.close() }
    }

    @Test fun clearedSettingsResetOnReusedPrivateSessionAndSnapshotStaysStable() = runTest {
        val f = fixture().apply { execOptions = setOf("host") }
        val c = Case(f, scriptedModel { _, _ -> flow { awaitCancellation() } }, CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)))
        try {
            c.room = c.rooms.create(CreateRoomParams("Peers", f.members.map { it.id }))
            c.session = c.rooms.createSession(c.room.id)
            c.rooms.configure(ConfigureRoomSessionParams(c.room.id, c.session.id, "test", SessionSettings(exec = "host", workspace = listOf("/work"))))
            c.send(listOf("test")); runCurrent()
            val first = assertNotNull(c.view().progress)
            assertEquals("host", first.effective?.exec)
            c.rooms.configure(ConfigureRoomSessionParams(c.room.id, c.session.id, "test", SessionSettings()))
            assertEquals("host", c.view().progress?.effective?.exec)
            c.rooms.stop(c.ref); runCurrent()
            c.send(listOf("test")); runCurrent()
            assertEquals(first.sessionId, c.view().progress?.sessionId)
            assertEquals("none", c.view().progress?.effective?.exec)
            assertEquals(SessionSettings(), f.store.session(f.agent, first.sessionId).settings)
        } finally { c.close() }
    }

    @Test fun rawAssistantOutputCannotBecomeAnImplicitPublicReply() = runTest {
        val c = Case(fixture(), scriptedModel { _, _ -> flowOf(StreamFrame.TextDelta("Private unintentional output"), StreamFrame.End()) }, CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)))
        try {
            c.start(listOf("test")); advanceUntilIdle()
            assertTrue(assertNotNull(c.view().error).contains("No Room outcome"))
            assertEquals(1, c.view().session.messages.size)
        } finally { c.close() }
    }
    @Test fun strictTerminalApprovalUsesOriginalBrokerAndFinishesWithoutAnotherModelCall() = runTest {
        val f = fixture().apply { members = members.map { it.copy(config = it.config.copy(approvals = "strict")) } }
        var requests = 0
        val c = Case(f, scriptedModel { _, tools -> requests++; roomReply(tools, "Approved reply") }, CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)))
        try {
            c.start(listOf("test")); runCurrent()
            val progress = assertNotNull(c.view().progress)
            val approval = assertNotNull(progress.live.approval)
            assertEquals("public_reply", approval.identity?.localId)
            assertTrue(c.runs.isActive(progress.sessionId))
            assertEquals(1, c.view().session.messages.size)
            assertTrue(c.harness.approvals.respond(approval.approvalId, ApprovalDecision.AllowOnce))
            advanceUntilIdle()
            assertEquals(1, requests)
            assertNull(c.view().error)
            assertEquals("Approved reply", c.view().session.messages.last().text)
            assertTrue(c.runs.sessions().isEmpty())
        } finally { c.close() }
    }

    @Test fun stopWhileAskingUserClosesBrokerAndDoesNotStartPendingPeerOrPublishLateOutput() = runTest {
        val f = fixture().apply { members = members.map { it.copy(config = it.config.copy(tools = listOf("ask_user"))) } }
        var requests = 0
        val c = Case(f, scriptedModel { _, tools ->
            requests++
            val ask = tools.single { it.description.startsWith("Ask the user") }
            flowOf(StreamFrame.TextDelta("PRIVATE_PROMPT"), StreamFrame.ToolCallComplete("question", ask.name, """{"questions":[{"id":"choice","label":"Choose?","kind":"text"}]}"""), StreamFrame.End())
        }, CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)))
        try {
            c.start(); runCurrent()
            val question = assertNotNull(c.view().progress?.live?.questions)
            assertEquals("", c.view().progress?.live?.text)
            c.rooms.stop(c.ref); advanceUntilIdle()
            assertFalse(c.harness.questions.respond(question.questionsId, mapOf("choice" to listOf("late answer"))))
            assertEquals(1, requests)
            assertEquals(1, c.view().session.messages.size)
            assertFalse(c.view().running)
            assertTrue(c.runs.sessions().isEmpty())
        } finally { c.close() }
    }

    @Test fun terminalInsideNestedToolRunPreventsLaterNestedSideEffects() = runTest {
        var sideEffects = 0
        var requests = 0
        val plugin = object : HarnessPlugin {
            override val definition = PluginDefinition("test.nested", "Nested")
            override suspend fun prepare(run: RunContext, activate: Boolean) = PluginTools(listOf(object : Tool {
                override val descriptor = ToolDescriptor("nested", "Nested calls")
                override val risk = Risk.Low
                override suspend fun execute(args: JsonObject): String = error("ToolRun required")
                override suspend fun execute(args: JsonObject, callId: String, run: ToolRun): String {
                    val terminal = run.call(run.tools.single { it.name.contains("pass_turn") }.name, "{}")
                    assertFalse(terminal.isError)
                    val after = run.call(run.tools.single { it.description == "Side effect" }.name, "{}")
                    assertTrue(after.isError)
                    return "Nested turn passed"
                }
            }, object : Tool {
                override val descriptor = ToolDescriptor("side", "Side effect")
                override val risk = Risk.Low
                override suspend fun execute(args: JsonObject): String { sideEffects++; return "ran" }
            }))
        }
        val f = RuntimeFixture(listOf(plugin)).apply { members += agent.copy(id = "pip") }
        val c = Case(f, scriptedModel { _, tools ->
            requests++
            flowOf(StreamFrame.ToolCallComplete("nested", tools.single { it.description == "Nested calls" }.name, "{}"), StreamFrame.End())
        }, CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)))
        try {
            c.start(listOf("test")); advanceUntilIdle()
            assertNull(c.view().error)
            assertEquals(1, requests)
            assertEquals(0, sideEffects)
            assertEquals(1, c.view().session.messages.size)
        } finally { c.close() }
    }

    @Test fun actualUsageStopsFurtherModelRequestsAndExactFinishedBudgetIsClean() = runTest {
        for (terminal in listOf(false, true)) {
            val f = fixture()
            val plugin = object : Tool {
                override val descriptor = ToolDescriptor("read", "Measured read")
                override val risk = Risk.Low
                override suspend fun execute(args: JsonObject) = "Read"
            }
            f.members = f.members.map { it.copy(config = it.config.copy(tools = listOf("web_fetch"))) }
            f.fetch = { plugin }
            var requests = 0
            val c = Case(f, scriptedModel { _, tools ->
                requests++
                val target = tools.single { if (terminal) it.name.contains("public_reply") else it.description == "Measured read" }
                flowOf(StreamFrame.ToolCallComplete("measured", target.name, if (terminal) """{"text":"Answer"}""" else "{}"),
                    StreamFrame.End(metaInfo = ResponseMetaInfo.Empty.copy(inputTokensCount = 15, outputTokensCount = 5)))
            }, CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)), Rooms.Limits(tokens = 20))
            try {
                c.start(listOf("test")); advanceUntilIdle()
                assertEquals(1, requests)
                if (terminal) { assertNull(c.view().error); assertEquals(2, c.view().session.messages.size) }
                else { assertTrue(assertNotNull(c.view().error).contains("token budget")); assertEquals(1, c.view().session.messages.size) }
                assertTrue(c.runs.sessions().isEmpty())
            } finally { c.close() }
        }
    }

    @Test fun multipleDistinctPendingRequestsAreFulfilledInOneMemberTurn() = runTest {
        val f = fixture().apply { members += agent.copy(id = "third", name = "Third") }
        var pip = 0
        val c = Case(f, scriptedModel { prompt, tools ->
            val context = prompt.messages.joinToString()
            when {
                "You are test." in context -> roomCall(tools, "address_member", """{"agentId":"pip","requestId":"a","text":"First question"}""")
                "You are third." in context -> roomCall(tools, "address_member", """{"agentId":"pip","requestId":"b","text":"Different question"}""")
                else -> { pip++; roomResult(tools, listOf("a", "b"), "Both results together") }
            }
        }, CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)))
        try {
            c.start(listOf("test", "third", "pip")); advanceUntilIdle()
            assertNull(c.view().error)
            assertEquals(1, pip)
            assertEquals(listOf("test", "third"), c.view().session.messages.last().recipients)
            assertEquals(4, c.view().session.messages.size)
        } finally { c.close() }
    }

    @Test fun newSchedulerReusesIdentityButNeverResumesInterruptedRequestsOrUnansweredTools() = runTest {
        val f = fixture()
        var atlas = 0
        var pip = 0
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val c = Case(f, scriptedModel { prompt, tools ->
            if ("You are test." in prompt.messages.joinToString()) {
                atlas++
                roomCall(tools, "address_member", """{"agentId":"pip","requestId":"old-root-work","text":"Old work","waitForResult":true}""")
            } else if (++pip == 1) flow { emit(StreamFrame.TextDelta("Interrupted private work")); awaitCancellation() }
            else {
                val context = prompt.messages.joinToString()
                assertTrue("Previous in-process work was interrupted" in context)
                assertTrue("Not run: previous Room turn interrupted" in context)
                roomReply(tools, "New root answer")
            }
        }, scope)
        try {
            c.start(listOf("test")); runCurrent()
            val oldPrivate = c.view().session.memberExecutions.getValue("pip").sessionId
            c.rooms.stop(c.ref); advanceUntilIdle()
            f.store.append(f.members.last(), oldPrivate, SessionEntry.ToolCall("orphan", "old_tool", "{}"))
            val restarted = Rooms(c.harness, c.runs, scope)
            runCurrent()
            assertFalse(restarted.view(c.ref).running)
            assertEquals(1, atlas); assertEquals(1, pip)
            assertEquals(2, restarted.view(c.ref).session.messages.size)
            restarted.send(SendRoomParams(c.room.id, c.session.id, "A new root", listOf("pip")))
            advanceUntilIdle()
            val view = restarted.view(c.ref)
            assertNull(view.error)
            assertEquals(oldPrivate, view.session.memberExecutions.getValue("pip").sessionId)
            assertFalse(view.session.memberExecutions.getValue("pip").interrupted)
            assertEquals(1, atlas); assertEquals(2, pip)
            assertEquals("New root answer", view.session.messages.last().text)
            assertTrue(f.store.entries(f.members.last(), oldPrivate).filterIsInstance<SessionEntry.ToolResult>().single { it.id == "orphan" }.isError)
            restarted.stopAll()
        } finally { c.close() }
    }

    @Test fun perMemberBudgetRejectsUnserviceableWaitingContinuationBeforeDispatchingPeer() = runTest {
        var calls = 0
        val c = Case(fixture(), scriptedModel { _, tools ->
            if (++calls == 1) roomCall(tools, "address_member", """{"agentId":"pip","text":"Need a continuation","waitForResult":true}""")
            else roomReply(tools, "Answer without more work")
        }, CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)), Rooms.Limits(perMember = 1))
        try {
            c.start(listOf("test")); advanceUntilIdle()
            assertEquals(2, calls)
            assertTrue(assertNotNull(c.view().error).contains("participation limit"))
            assertEquals(setOf("test"), c.view().session.memberExecutions.keys)
            assertEquals(2, c.view().session.messages.size)
        } finally { c.close() }
    }

    @Test fun repeatingAnOpenRequestCannotAbandonIncomingWorkOrLeaveAWaitingCycle() = runTest {
        var atlas = 0
        var pip = 0
        val c = Case(fixture(), scriptedModel { prompt, tools ->
            if ("You are test." in prompt.messages.joinToString()) when (++atlas) {
                1, 2 -> roomCall(tools, "address_member", """{"agentId":"pip","requestId":"a","text":"Work A","waitForResult":true}""", "a-$atlas")
                3 -> roomCall(tools, "pass_turn")
                else -> roomReply(tools, "Continued with A's result")
            } else when (++pip) {
                1 -> roomCall(tools, "address_member", """{"agentId":"test","requestId":"b","text":"Work B","waitForResult":true}""")
                else -> roomResult(tools, listOf("a"), "A's result")
            }
        }, CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)))
        try {
            c.start(listOf("test")); advanceUntilIdle()
            assertNull(c.view().error)
            assertEquals(4, atlas); assertEquals(2, pip)
            assertEquals(1, c.view().session.messages.count { it.text == "Work A" })
            assertEquals("Continued with A's result", c.view().session.messages.last().text)
            assertTrue(c.runs.sessions().isEmpty())
        } finally { c.close() }
    }

}
