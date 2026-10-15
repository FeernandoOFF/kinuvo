package kinuvo.app

import kinuvo.app.session.*

import kinuvo.protocol.ToolIdentity
import kinuvo.protocol.PluginInstanceId
import kinuvo.protocol.AgentEvent
import kinuvo.protocol.LiveState
import kinuvo.protocol.SessionEntry
import kinuvo.protocol.ReflectionResult
import kotlin.test.Test
import kotlin.test.assertEquals

class SessionDataTest {
    private val identity = ToolIdentity(PluginInstanceId("kinuvo.exec"), "shell")
    private val approval = AgentEvent.ApprovalRequested("a1", "c1", "model_alias", "{}", identity = identity)

    @Test
    fun messagesKeepTheirKeyFromLiveToSaved() {
        val live = SessionData("main", entries = listOf(SessionEntry.UserMessage("hi")), live = LiveState(running = true, messageId = "m1", text = "hel", reasoning = "hm"))
        val saved = live.copy(
            entries = live.entries + SessionEntry.Reasoning("hm", messageId = "m1") + SessionEntry.AssistantMessage("hello", "m1"),
            live = LiveState(running = true, messageId = "m1"),
        )
        assertEquals(listOf("entry-0", "reasoning-m1", "text-m1"), live.items.map { it.id })
        assertEquals(live.items.map { it.id }, saved.items.map { it.id })
    }

    @Test
    fun toolResultsAndApprovalsJoinTheirCallByModelId() {
        val data = SessionData(
            "main",
            firstSeq = 10,
            entries = listOf(
                SessionEntry.ToolCall("c0", "model_alias", "{}", identity),
                SessionEntry.ToolResult("c0", "shell", "ok"),
                SessionEntry.ToolCall("c1", "model_alias", "{}", identity),
            ),
            live = LiveState(running = true, approval = approval),
        )
        val (done, waiting) = data.items.map { it as ChatItem.ToolCall }
        assertEquals(identity, done.identity)
        assertEquals(identity, waiting.identity)
        assertEquals("ok" to null, done.result to done.approval)
        assertEquals("tool-c1" to approval, waiting.id to waiting.approval)
    }

    @Test
    fun reflectionKeepsItsKeyAndResultWhenReopened() {
        val result = ReflectionResult(knowledge = 1, memory = 2, userNotes = 1, memoryChanges = listOf(
            kinuvo.protocol.ReflectionMemoryChange("memory", "- Old lesson\n", "- New lesson\n"),
        ))
        val entry = SessionEntry.Reflection("r1", 123, result)
        val live = SessionData("main", live = LiveState(reflectionId = "r1"))
        val saved = live.copy(entries = listOf(entry), live = live.live.on(entry))
        assertEquals(listOf(ChatItem.Reflection("reflection-r1")), live.items)
        assertEquals(listOf(ChatItem.Reflection("reflection-r1", result)), saved.items)
        assertEquals(saved.items, SessionData("main", entries = listOf(entry)).items)
        assertEquals(listOf(ChatRow.Single(saved.items.single())), saved.items.rows())
    }

    @Test
    fun reflectionReviewsFoldIntoSavedProposalCards() {
        val result = ReflectionResult(proposals = listOf(kinuvo.protocol.ReflectionProposal("p1", "knowledge",
            kotlinx.serialization.json.JsonObject(emptyMap()), "A decision", "user-0")))
        val data = SessionData("main", entries = listOf(SessionEntry.Reflection("r1", 0, result), SessionEntry.ReflectionReview("p1", false)))
        assertEquals(listOf(ChatItem.Reflection("reflection-r1", result, mapOf("p1" to false))), data.items)
    }

    @Test
    fun openFormJoinsItsCall() {
        val asked = AgentEvent.QuestionsAsked("q1", "c1", null, emptyList())
        val data = SessionData("main", entries = listOf(SessionEntry.ToolCall("c1", "ask_user", "{}")), live = LiveState(running = true, questions = asked))
        assertEquals(asked, (data.items.single() as ChatItem.ToolCall).questions)
    }
}
