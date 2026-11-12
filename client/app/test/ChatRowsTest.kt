package kinuvo.app

import kinuvo.protocol.approval.InteractionId
import kinuvo.app.connection.*
import kinuvo.app.session.*
import kinuvo.app.agent.*

import kinuvo.protocol.session.AgentEvent
import kinuvo.protocol.approval.PendingInteraction
import kinuvo.protocol.usage.RequestKind
import kinuvo.protocol.usage.RequestMetrics
import kinuvo.protocol.reflection.ReflectionResult
import kinuvo.protocol.session.ToolCallId
import kotlin.test.Test
import kotlin.test.assertEquals

class ChatRowsTest {
    private fun tool(id: String, name: String = "shell", result: String? = "ok") = ChatItem.ToolCall(id, ToolCallId(id), name, "{}", result)

    private fun metrics(id: String, at: Long = 0) = RequestMetrics(id, at, RequestKind.Chat, "codex", "model", "medium", 100)

    @Test
    fun generatedImagesStayOutsideCollapsedSteps() {
        val rows = listOf(tool("before"), tool("image", "generate_image"), tool("after")).rows()
        assertEquals(listOf("before", "image", "after"), rows.map { it.key })
    }

    @Test
    fun measurementsDoNotSplitToolGroups() {
        val items = listOf(tool("t1"), ChatItem.Measurement("m1", metrics("m1")), tool("t2"), ChatItem.Assistant("a1", "done"))
        val rows = items.rows()
        assertEquals(2, rows.size)
        assertEquals(listOf("t1", "t2"), (rows.first() as ChatRow.Steps).tools.map { it.id })
        assertEquals("a1", rows.last().key)
    }

    @Test
    fun statisticsIncludeReflectionAndDeduplicateRequestsNewestFirst() {
        val first = metrics("first", 1)
        val last = metrics("last", 2).copy(kind = RequestKind.Reflection)
        val items = listOf(ChatItem.Measurement("m1", first), ChatItem.Measurement("m2", last), ChatItem.Reflection("r1", ReflectionResult(measurements = listOf(last))))
        assertEquals(listOf(last, first), items.requestMetrics())
    }

    @Test
    fun stepsBetweenMessagesGroupAndPendingCallsStayOut() {
        val approval = PendingInteraction.Approval(InteractionId("a1"), ToolCallId("t4"), "shell", "{}")
        val items = listOf(
            ChatItem.User("u1", "hi"),
            ChatItem.Reasoning("r1", "hm"), tool("t1"), tool("t2"),
            ChatItem.Assistant("a1", "done"),
            tool("t3"), tool("t4", result = null).copy(approval = approval), tool("t5", "artifact", "{}"),
        )
        assertEquals(
            listOf("u1", "steps:r1", "a1", "steps:t3", "t4", "t5"),
            items.rows().map { if (it is ChatRow.Steps) "steps:${it.key}" else it.key },
        )
    }

}
