package kinuvo.providers.litert

import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Role
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals

class LiteRtHistoryTest {
    @Test fun conversationAcceptsNoTools() {
        ConversationConfig(tools = emptyList(), automaticToolCalling = false)
    }

    private val request = RequestMetaInfo.Empty
    private val response = ResponseMetaInfo.Empty

    @Test fun toolCallsStayOnTheModelTurnAndResultsBecomeToolMessages() {
        val history = liteRtHistory(listOf(
            Message.System("Be brief", request),
            Message.User("Weather in Paris?", request),
            Message.Assistant(listOf(MessagePart.Tool.Call("1", "weather", buildJsonObject { put("city", "Paris") })), response),
            Message.User(listOf(MessagePart.Tool.Result("1", "weather", """{"celsius":12}""")), request),
        ))
        assertEquals(listOf(Role.USER, Role.MODEL, Role.TOOL), history.map { it.role })
        assertEquals(mapOf<String, Any?>("city" to "Paris"), history[1].toolCalls.single().arguments)
        val result = history[2].contents.contents.single() as Content.ToolResponse
        assertEquals("weather" to mapOf<String, Any?>("celsius" to 12L), result.name to result.response)
    }

    @Test fun failedAndPlainResultsKeepTheirText() {
        val history = liteRtHistory(listOf(Message.User(listOf(MessagePart.Tool.Result("1", "fetch", "timed out", isError = true)), request)))
        assertEquals(mapOf("error" to "timed out"), (history.single().contents.contents.single() as Content.ToolResponse).response)
    }

    @Test fun argumentsConvertToJson() {
        assertEquals(buildJsonObject { put("n", 3); put("x", 1.5); putJsonArray("tags") { add("a") }; put("on", true) },
            mapOf("n" to 3.0, "x" to 1.5, "tags" to listOf("a"), "on" to true).toJson())
    }
}
