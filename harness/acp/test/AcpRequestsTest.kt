package kinuvo.harness.acp

import kinuvo.protocol.*
import kinuvo.protocol.approval.*
import kinuvo.protocol.execution.*
import kinuvo.harness.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlin.test.*

class AcpRequestsTest {
    private fun decode(json: String) = decodePrompt("source", AcpRequestKind.Question, ProtocolJson.parseToJsonElement(json))
    @Test fun formsPreserveTypedChoicesDefaultsAndValidateBeforeEncoding() {
        val prompt = decode("""{"message":"Visit https://example.com/private?token=secret","requestedSchema":{"type":"object","required":["n"],"properties":{"n":{"type":"integer","default":2},"ok":{"type":"boolean"},"items":{"type":"array","items":{"oneOf":[{"const":"a","title":"Alpha"}]}}}}}""") 
        val asked = prompt.interaction as PendingInteraction.Questions
        assertEquals("Visit example.com", asked.title)
        assertEquals(listOf("2"), asked.questions[0].default)
        val result = prompt.encodeAnswer(ProtocolJson.parseToJsonElement("""{"answers":{"n":["3"],"ok":["Yes"],"items":["Alpha"]}}"""))
        assertEquals(ProtocolJson.parseToJsonElement("""{"action":"accept","content":{"n":3,"ok":true,"items":["a"]}}"""), result)
        assertFailsWith<IllegalArgumentException> { prompt.encodeAnswer(ProtocolJson.parseToJsonElement("""{"answers":{"n":["oops"]}}""")) }
        assertEquals("decline", prompt.encodeAnswer(JsonNull).jsonObject["action"]!!.jsonPrimitive.content)
    }
    @Test fun linksAreValidatedAndRedacted() {
        assertIs<PendingInteraction.Unsupported>(decode("""{"mode":"url","url":"file:///private","message":"Sign in"}""").interaction)
        val prompt = decode("""{"mode":"url","url":"https://example.com/path?token=secret","message":"Open https://example.com/path?token=secret"}""")
        val link = prompt.interaction as PendingInteraction.OpenLink
        assertEquals("example.com", link.site)
        assertEquals("Open example.com", link.message)
        assertEquals("accept", prompt.encodeAnswer(ProtocolJson.parseToJsonElement("""{"opened":true}""")).jsonObject["action"]!!.jsonPrimitive.content)
    }
    @Test fun cancellationReleasesWaitersAndKeepsNextTurnUsable() = runBlocking {
        val state = ExecutionState("s")
        val broker = ExecutionBroker(state)
        val owner = AcpRequests(broker)
        val prompt = AcpPrompt(PendingInteraction.HarnessApproval("source", "Run?", null, listOf(ExternalApprovalOption("yes", "Allow", "allow_once"))))
        val first = async { owner.ask(prompt) }
        withTimeout(1000) { while (state.snapshot().pending.isEmpty()) yield() }
        val old = state.snapshot().pending.single().id
        owner.cancel()
        assertTrue(first.isCancelled)
        assertTrue(state.snapshot().pending.isEmpty())
        assertFalse(broker.answer(old, JsonNull))
        val second = async { owner.ask(prompt) }
        withTimeout(1000) { while (state.snapshot().pending.isEmpty()) yield() }
        assertTrue(broker.answer(state.snapshot().pending.single().id, JsonNull))
        assertEquals(JsonNull, second.await())
    }

}
