package kinuvo.harness.litert

import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import com.google.ai.edge.litertlm.Content
import kinuvo.server.plugin.takeWhole
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import com.google.ai.edge.litertlm.Message as LiteRtMessage

class LiteRtTextTest {
    private val request = RequestMetaInfo.Empty

    @Test fun everythingSentToLiteRtIsValidUtf8() {
        val page = "Bahasa Hulontalo \n 𐌲𐌿𐍄𐌹𐍃𐌺"
        val history = liteRtHistory(listOf(
            Message.User("Hi 😀 日本語 गोंयची कोंकणी", request),
            Message.Assistant(listOf(MessagePart.Tool.Call("1", "web_fetch", buildJsonObject { put("url", "https://example.com/\uD83D") })), ResponseMetaInfo.Empty),
            Message.User(listOf(
                MessagePart.Tool.Result("1", "web_fetch", page.takeWhole(page.length - 1)),
                MessagePart.Tool.Result("2", "web_fetch", page.take(page.length - 1)),
                MessagePart.Tool.Result("3", "json", """{"title":"\uDF32 😀"}"""),
            ), request),
        ))
        val texts = history.flatMap { it.texts() }
        texts.forEach { assertTrue(isNativeSafe(it), "Not valid UTF-8 for LiteRT: $it") }
        assertEquals("Hi � 日本語 गोंयची कोंकणी", texts.first())
        assertTrue("Bahasa Hulontalo \n �����" in texts)
    }

    @Test fun textWithoutSurrogatesIsUnchanged() {
        val text = "日本語 गोंयची Ελληνικά �"
        assertTrue(text.liteRtText() === text)
    }

    private fun LiteRtMessage.texts(): List<String> = contents.contents.flatMap { content ->
        when (content) {
            is Content.Text -> listOf(content.text)
            is Content.ToolResponse -> leaves(content.response)
            else -> emptyList()
        }
    } + toolCalls.flatMap { leaves(it.arguments) }

    private fun leaves(value: Any?): List<String> = when (value) {
        is String -> listOf(value)
        is Map<*, *> -> value.flatMap { leaves(it.key) + leaves(it.value) }
        is Iterable<*> -> value.flatMap(::leaves)
        else -> emptyList()
    }

    /** Whether JNI's modified UTF-8 encoding of [text] is valid UTF-8 for LiteRT-LM's JSON parser. */
    private fun isNativeSafe(text: String): Boolean {
        val bytes = ByteArrayOutputStream().also { DataOutputStream(it).writeUTF(text) }.toByteArray().drop(2).toByteArray()
        return runCatching { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)) }.isSuccess
    }
}
