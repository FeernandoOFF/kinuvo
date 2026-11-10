package kinuvo.protocol

import kotlin.test.*

class ExecutionContractsTest {
    @Test fun noticesAndNormalizedPromptsRoundTrip() {
        val notice = SessionEntry.Notice("n", "interrupted", "Interrupted", listOf(
            SessionEntry.UserMessage("", submissionId = "s"), SessionEntry.AssistantMessage("draft", "m"),
        ))
        assertEquals(notice, ProtocolJson.decodeFromString(SessionEntry.serializer(), ProtocolJson.encodeToString(SessionEntry.serializer(), notice)))
        val interactions = listOf(
            PendingInteraction.Approval("p", "tool", "shell", "{}", risk = Risk.High),
            PendingInteraction.Questions("q", "tool", "Form", listOf(Question("x", "X"))),
            PendingInteraction.HarnessApproval("a", "Allow", null, listOf(ExternalApprovalOption("once", "Once", "allow_once"))),
            PendingInteraction.OpenLink("u", "Sign in", "https://example.com/", "example.com"),
            PendingInteraction.Unsupported("x", "Unsupported"),
        )
        interactions.forEach { interaction ->
            val snapshot = ExecutionSnapshot("s", pending = listOf(interaction))
            assertEquals(snapshot, ProtocolJson.decodeFromString(ExecutionSnapshot.serializer(), ProtocolJson.encodeToString(ExecutionSnapshot.serializer(), snapshot)))
        }
        assertNull(ProtocolJson.decodeFromString(SessionEntry.UserMessage.serializer(), """{"text":"old"}""").submissionId)
    }

    @Test fun sessionOrderUsesKnownTimeThenFrozenLegacyOrder() {
        val rows = listOf(
            SessionInfo("uuid-z", "a", "Z", legacyOrder = 0),
            SessionInfo("uuid-a", "a", "A", legacyOrder = 1),
            SessionInfo("old", "a", "Old", createdAt = 10),
            SessionInfo("new", "a", "New", createdAt = 20),
            SessionInfo("tie-b", "a", "B"), SessionInfo("tie-a", "a", "A"),
        )
        assertEquals(listOf("new", "old", "uuid-z", "uuid-a", "tie-a", "tie-b"), rows.sortedWith(SessionOrder).map { it.id })
    }

    @Test fun interleavedExternalUpdatesReplaceInPlaceAndCommitByTypedIdentity() {
        val call = SessionEntry.ToolCall("t", "shell", "{}")
        val result = SessionEntry.ToolResult("t", "shell", "done")
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
