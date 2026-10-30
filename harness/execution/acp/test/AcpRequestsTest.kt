package kinuvo.execution.acp

import kinuvo.protocol.*
import kinuvo.execution.api.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlin.test.*

class AcpRequestsTest {
    private fun decode(json: String) = ExecutionRequest("source", ExecutionRequestKind.Question, ProtocolJson.parseToJsonElement(json)).decodePrompt()
    @Test fun formsPreserveTypedChoicesDefaultsAndValidateBeforeEncoding() {
        val prompt = decode("""{"message":"Visit https://example.com/private?token=secret","requestedSchema":{"type":"object","required":["n"],"properties":{"n":{"type":"integer","default":2},"ok":{"type":"boolean"},"items":{"type":"array","items":{"oneOf":[{"const":"a","title":"Alpha"}]}}}}}""") as ExecutionPrompt.Questions
        assertEquals("Visit example.com", prompt.asked.title)
        assertEquals(listOf("2"), prompt.asked.questions[0].default)
        val result = prompt.encodeAnswer(ProtocolJson.parseToJsonElement("""{"answers":{"n":["3"],"ok":["Yes"],"items":["Alpha"]}}"""))
        assertEquals(ProtocolJson.parseToJsonElement("""{"action":"accept","content":{"n":3,"ok":true,"items":["a"]}}"""), result)
        assertFailsWith<IllegalArgumentException> { prompt.encodeAnswer(ProtocolJson.parseToJsonElement("""{"answers":{"n":["oops"]}}""")) }
        assertEquals("decline", prompt.encodeAnswer(JsonNull).jsonObject["action"]!!.jsonPrimitive.content)
    }
    @Test fun linksAreValidatedAndRedacted() {
        assertIs<ExecutionPrompt.Unsupported>(decode("""{"mode":"url","url":"file:///private","message":"Sign in"}"""))
        val prompt = decode("""{"mode":"url","url":"https://example.com/path?token=secret","message":"Open https://example.com/path?token=secret"}""") as ExecutionPrompt.OpenLink
        assertEquals("example.com", prompt.site)
        assertEquals("Open example.com", prompt.message)
        assertEquals("accept", prompt.encodeAnswer(ProtocolJson.parseToJsonElement("""{"opened":true}""")).jsonObject["action"]!!.jsonPrimitive.content)
    }
    @Test fun cancellationReleasesWaitersAndKeepsNextTurnUsable() = runBlocking {
        val state = ExecutionState("s")
        val broker = ExecutionBroker(state)
        val owner = AcpRequests(broker)
        val prompt = ExecutionPrompt.Approval("source", "Run?", null, listOf(ExternalApprovalOption("yes", "Allow", "allow_once")))
        val source = ExecutionRequest("source", ExecutionRequestKind.Approval, JsonNull, prompt = prompt)
        val first = async { owner.ask(source, prompt) }
        withTimeout(1000) { while (state.snapshot().pending.isEmpty()) yield() }
        val old = state.snapshot().pending.single().id
        owner.cancel()
        assertTrue(first.isCancelled)
        assertTrue(state.snapshot().pending.isEmpty())
        assertFalse(broker.answer(old, JsonNull))
        val second = async { owner.ask(source, prompt) }
        withTimeout(1000) { while (state.snapshot().pending.isEmpty()) yield() }
        assertTrue(broker.answer(state.snapshot().pending.single().id, JsonNull))
        assertEquals(JsonNull, second.await())
    }

}
