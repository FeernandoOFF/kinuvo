package kinuvo.harness.server

import kinuvo.harness.core.AgentModel
import kinuvo.harness.testing.*
import kinuvo.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class RoomWaitCapacityTest {
    private suspend fun TestScope.scenario(limits: Rooms.Limits, recipients: List<String> = emptyList(), model: AgentModel, verify: suspend (RoomView, RuntimeFixture) -> Unit) {
        val fixture = RuntimeFixture().apply { members += listOf(agent.copy(id = "pip"), agent.copy(id = "cedar")) }
        val harness = fixture.runtime(model)
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val runs = Runs(harness.log, scope)
        val rooms = Rooms(harness, runs, scope, limits)
        try {
            val room = rooms.create(CreateRoomParams("Peers", fixture.members.map { it.id }))
            val session = rooms.createSession(room.id)
            rooms.send(SendRoomParams(room.id, session.id, "Work", recipients))
            advanceUntilIdle()
            verify(rooms.view(RoomSessionRef(room.id, session.id)), fixture)
            assertTrue(runs.sessions().isEmpty())
        } finally { rooms.stopAll(); scope.cancel(); harness.close() }
    }

    @Test fun fourDispatchesRejectTheSecondWaitBeforeAcceptingIt() = runTest {
        var atlas = 0
        var pip = 0
        scenario(Rooms.Limits(dispatches = 4), model = scriptedModel { prompt, tools ->
            val context = prompt.messages.joinToString()
            when {
                "You are test." in context -> if (++atlas == 1) roomCall(tools, "address_member", """{"agentId":"cedar","requestId":"a","text":"Work A","waitForResult":true}""") else roomReply(tools, "A resumed")
                "You are pip." in context -> if (++pip == 1) roomCall(tools, "address_member", """{"agentId":"cedar","requestId":"b","text":"Unserviceable B","waitForResult":true}""") else {
                    assertTrue(prompt.messages.toolResults.last().isError)
                    roomReply(tools, "B answered without waiting")
                }
                else -> roomResult(tools, if ("b: pip -> cedar" in context) listOf("a", "b") else listOf("a"), "A's result")
            }
        }) { view, fixture ->
            assertTrue(view.session.messages.none { it.text == "Unserviceable B" }, "Reject an unserviceable wait before publishing it")
            assertTrue(assertNotNull(view.error).contains("participation limit"))
            assertEquals(2, atlas)
            assertEquals(2, pip)
            assertEquals("A resumed", view.session.messages.last().text)
            val private = view.session.memberExecutions.getValue("pip").sessionId
            assertTrue(fixture.store.entries(fixture.members[1], private).filterIsInstance<SessionEntry.ToolResult>().first().isError)
        }
    }

    @Test fun fiveDispatchesReserveBothOwnersAndResumeBothAtTheExactLimit() = runTest {
        var atlas = 0
        var pip = 0
        scenario(Rooms.Limits(dispatches = 5, perMember = 2, depth = 1), model = scriptedModel { prompt, tools ->
            val context = prompt.messages.joinToString()
            when {
                "You are test." in context -> if (++atlas == 1) roomCall(tools, "address_member", """{"agentId":"cedar","requestId":"a","text":"Work A","waitForResult":true}""") else roomReply(tools, "A resumed")
                "You are pip." in context -> if (++pip == 1) roomCall(tools, "address_member", """{"agentId":"cedar","requestId":"b","text":"Work B","waitForResult":true}""") else roomReply(tools, "B resumed")
                else -> roomResult(tools, listOf("a", "b"), "Both results")
            }
        }) { view, _ ->
            assertNull(view.error)
            assertEquals(2, atlas)
            assertEquals(2, pip)
            assertEquals(listOf("A resumed", "B resumed"), view.session.messages.takeLast(2).map { it.text })
        }
    }

    @Test fun nestedWaitReservesBothThePendingTurnAndItsStillAwaitedContinuation() = runTest {
        var atlas = 0
        var pip = 0
        var cedar = 0
        scenario(Rooms.Limits(dispatches = 7, perMember = 2), listOf("test"), scriptedModel { prompt, tools ->
            val context = prompt.messages.joinToString()
            when {
                "You are test." in context -> if (++atlas == 1) roomCall(tools, "address_member", """{"agentId":"pip","requestId":"a","text":"Work A","waitForResult":true}""") else roomReply(tools, "A resumed")
                "You are pip." in context -> if (++pip == 1) roomCall(tools, "address_member", """{"agentId":"cedar","requestId":"b","text":"Work B","waitForResult":true}""") else roomResult(tools, listOf("a"), "A's result")
                else -> if (++cedar == 1) roomCall(tools, "address_member", """{"agentId":"test","requestId":"c","text":"Unserviceable nested wait","waitForResult":true}""") else roomResult(tools, listOf("b"), "B's result")
            }
        }) { view, _ ->
            assertTrue(view.session.messages.none { it.text == "Unserviceable nested wait" }, "Reserve the owner's pending turn and later continuation together")
            assertTrue(assertNotNull(view.error).contains("participation limit"))
            assertEquals(2, atlas)
            assertEquals(2, pip)
            assertEquals(2, cedar)
            assertEquals("A resumed", view.session.messages.last().text)
        }
    }

    @Test fun nestedWaitCannotReserveBeyondTheDepthLimit() = runTest {
        var atlas = 0
        var pip = 0
        scenario(Rooms.Limits(dispatches = 3, depth = 1), listOf("test"), scriptedModel { prompt, tools ->
            if ("You are test." in prompt.messages.joinToString()) {
                if (++atlas == 1) roomCall(tools, "address_member", """{"agentId":"pip","requestId":"a","text":"Work A","waitForResult":true}""") else roomReply(tools, "A resumed")
            } else if (++pip == 1) roomCall(tools, "address_member", """{"agentId":"cedar","requestId":"b","text":"Beyond depth","waitForResult":true}""")
            else roomResult(tools, listOf("a"), "A's result")
        }) { view, _ ->
            assertTrue(assertNotNull(view.error).contains("participation limit"))
            assertEquals(setOf("test", "pip"), view.session.memberExecutions.keys)
            assertEquals(2, atlas)
            assertEquals(2, pip)
            assertTrue(view.session.messages.none { it.text == "Beyond depth" })
            assertEquals("A resumed", view.session.messages.last().text)
        }
    }

    @Test fun nestedWaitsToTheSameTargetCoalesceAndResolvedWaitsDoNotReserveAgain() = runTest {
        var atlas = 0
        var pip = 0
        var cedar = 0
        scenario(Rooms.Limits(dispatches = 8, perMember = 3, depth = 4), listOf("test"), scriptedModel { prompt, tools ->
            val context = prompt.messages.joinToString()
            when {
                "You are test." in context -> when (++atlas) {
                    1 -> roomCall(tools, "address_member", """{"agentId":"pip","requestId":"a","text":"Work A","waitForResult":true}""")
                    2 -> roomCall(tools, "address_member", """{"agentId":"pip","requestId":"d","text":"Distinct extra work","waitForResult":true}""")
                    else -> roomResult(tools, listOf("c"), "C's result")
                }
                "You are pip." in context -> when (++pip) {
                    1 -> roomCall(tools, "address_member", """{"agentId":"cedar","requestId":"b","text":"Work B","waitForResult":true}""")
                    2 -> roomResult(tools, listOf("a", "d"), "A and D results together")
                    else -> roomReply(tools, "B resumed")
                }
                else -> if (++cedar == 1) roomCall(tools, "address_member", """{"agentId":"test","requestId":"c","text":"Work C","waitForResult":true}""") else roomResult(tools, listOf("b"), "B's result")
            }
        }) { view, _ ->
            assertNull(view.error)
            assertEquals(3, atlas)
            assertEquals(3, pip)
            assertEquals(2, cedar)
            assertEquals("B resumed", view.session.messages.last().text)
        }
    }
}
