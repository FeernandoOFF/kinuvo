package kinuvo.app

import kinuvo.app.connection.*
import kinuvo.app.session.*
import kinuvo.app.agent.*

import kinuvo.protocol.AgentEvent
import kinuvo.protocol.RequestKind
import kinuvo.protocol.RequestMetrics
import kinuvo.protocol.ReflectionResult
import kotlin.test.Test
import kotlin.test.assertEquals

class ChatRowsTest {
    private fun tool(id: String, name: String = "shell", result: String? = "ok") = ChatItem.ToolCall(id, id, name, "{}", result)

    private fun metrics(id: String, at: Long = 0) = RequestMetrics(id, at, RequestKind.Chat, "codex", "model", "medium", 100)

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
        val approval = AgentEvent.ApprovalRequested("a1", "t4", "shell", "{}")
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
