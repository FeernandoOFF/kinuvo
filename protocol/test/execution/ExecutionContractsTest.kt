package kinuvo.protocol.execution

import kinuvo.protocol.ProtocolJson
import kinuvo.protocol.approval.PendingInteraction
import kinuvo.protocol.approval.Question
import kinuvo.protocol.approval.Risk
import kinuvo.protocol.session.AgentEvent
import kinuvo.protocol.session.LiveState
import kinuvo.protocol.session.SessionEntry
import kinuvo.protocol.session.SessionInfo
import kinuvo.protocol.session.SessionOrder
import kinuvo.protocol.session.SessionStatus
import kinuvo.protocol.agent.AgentId
import kinuvo.protocol.session.SessionId
import kinuvo.protocol.session.ToolCallId
import kotlin.test.*

class ExecutionContractsTest {
    @Test fun connectionsKeepTheirSerialNames() {
        assertEquals("""{"type":"builtin"}""", ProtocolJson.encodeToString(ExecutionConnection.serializer(), ExecutionConnection.BuiltIn))
        assertEquals(ExecutionConnection.Acp(listOf("agent")), ProtocolJson.decodeFromString(ExecutionConnection.serializer(), """{"type":"kinuvo.protocol.ExecutionConnection.Acp","command":["agent"]}"""))
        assertEquals(ExecutionConnection.Remote("source"), ProtocolJson.decodeFromString(ExecutionConnection.serializer(), """{"type":"kinuvo.protocol.ExecutionConnection.Remote","serverId":"source"}"""))
    }

    @Test fun noticesAndNormalizedPromptsRoundTrip() {
        val notice = SessionEntry.Notice("n", "interrupted", "Interrupted", listOf(
            SessionEntry.UserMessage("", submissionId = "s"), SessionEntry.AssistantMessage("draft", "m"),
        ))
        assertEquals(notice, ProtocolJson.decodeFromString(SessionEntry.serializer(), ProtocolJson.encodeToString(SessionEntry.serializer(), notice)))
        val interactions = listOf(
            PendingInteraction.Approval("p", ToolCallId("tool"), "shell", "{}", risk = Risk.High),
            PendingInteraction.Questions("q", ToolCallId("tool"), "Form", listOf(Question("x", "X"))),
            PendingInteraction.HarnessApproval("a", "Allow", null, listOf(ExternalApprovalOption("once", "Once", "allow_once"))),
            PendingInteraction.OpenLink("u", "Sign in", "https://example.com/", "example.com"),
            PendingInteraction.Unsupported("x", "Unsupported"),
        )
        interactions.forEach { interaction ->
            val snapshot = ExecutionSnapshot(SessionId("s"), pending = listOf(interaction))
            assertEquals(snapshot, ProtocolJson.decodeFromString(ExecutionSnapshot.serializer(), ProtocolJson.encodeToString(ExecutionSnapshot.serializer(), snapshot)))
        }
        assertNull(ProtocolJson.decodeFromString(SessionEntry.UserMessage.serializer(), """{"text":"old"}""").submissionId)
    }

    @Test fun sessionOrderUsesKnownTimeThenFrozenLegacyOrder() {
        val rows = listOf(
            SessionInfo(SessionId("uuid-z"), AgentId("a"), "Z", legacyOrder = 0),
            SessionInfo(SessionId("uuid-a"), AgentId("a"), "A", legacyOrder = 1),
            SessionInfo(SessionId("old"), AgentId("a"), "Old", createdAt = 10),
            SessionInfo(SessionId("new"), AgentId("a"), "New", createdAt = 20),
            SessionInfo(SessionId("tie-b"), AgentId("a"), "B"), SessionInfo(SessionId("tie-a"), AgentId("a"), "A"),
        )
        assertEquals(listOf("new", "old", "uuid-z", "uuid-a", "tie-a", "tie-b"), rows.sortedWith(SessionOrder).map { it.id.value })
    }

    @Test fun interleavedExternalUpdatesReplaceInPlaceAndCommitByTypedIdentity() {
        val call = SessionEntry.ToolCall(ToolCallId("t"), "shell", "{}")
        val result = SessionEntry.ToolResult(ToolCallId("t"), "shell", "done")
        val live = LiveState().on(AgentEvent.ExecutionPhaseChanged(ExecutionPhase.Running))
            .on(AgentEvent.TextMessageContent("a", "first"))
            .on(AgentEvent.ExecutionEntryChanged(call))
            .on(AgentEvent.TextMessageContent("b", "second"))
            .on(AgentEvent.TextMessageContent("a", " updated"))
            .on(AgentEvent.ExecutionEntryChanged(result))
        assertEquals(listOf(SessionEntry.AssistantMessage("first updated", "a"), call, SessionEntry.AssistantMessage("second", "b"), result), live.entries)
        assertEquals(listOf(call, SessionEntry.AssistantMessage("second", "b"), result), live.on(SessionEntry.AssistantMessage("first updated", "a")).entries)
        assertEquals(listOf(SessionEntry.AssistantMessage("first updated", "a"), SessionEntry.AssistantMessage("second", "b"), result), live.on(call).entries)
        assertTrue(live.on(SessionEntry.Notice("n", "interrupted", "Draft", live.entries)).entries.isEmpty())
    }

    @Test fun pendingRequestsAreIndependentAndLossClearsThem() {
        val a = PendingInteraction.Unsupported("a", "Unsupported")
        val b = a.copy(id = "b")
        val live = LiveState().on(AgentEvent.ExecutionPhaseChanged(ExecutionPhase.Running))
            .on(AgentEvent.InteractionRequested(a)).on(AgentEvent.InteractionRequested(b))
        assertEquals(SessionStatus.Waiting, live.status)
        assertEquals(listOf(b), live.on(AgentEvent.InteractionResolved("a")).pending)
        assertEquals(emptyList(), live.on(AgentEvent.ExecutionPhaseChanged(ExecutionPhase.Lost)).pending)
        assertEquals(SessionStatus.Idle, live.on(AgentEvent.ExecutionPhaseChanged(ExecutionPhase.Lost)).status)
    }
}
