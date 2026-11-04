package kinuvo.harness.litert

import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Role
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals

class LiteRtHistoryTest {
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

    @Test fun pluginAliasesBecomeShortNamesUnlessTheyClash() {
        val memory = ai.koog.agents.core.tools.ToolDescriptor("core__default__memory__0123456789abcdef", "")
        val mine = ai.koog.agents.core.tools.ToolDescriptor("mcp__notes__search__0123456789abcdef", "")
        val theirs = ai.koog.agents.core.tools.ToolDescriptor("mcp__mail__search__fedcba9876543210", "")
        assertEquals(mapOf(memory.name to "memory", mine.name to mine.name, theirs.name to theirs.name), shortNames(listOf(memory, mine, theirs)))
    }
}
