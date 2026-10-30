package kinuvo.execution.acp

import kinuvo.execution.api.*
import kinuvo.protocol.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

class AcpReducerTest {
    private class Recording : ExecutionSink {
        val events = mutableListOf<AgentEvent>()
        val batches = mutableListOf<List<SessionEntry>>()
        val order = mutableListOf<String>()
        override suspend fun publish(event: AgentEvent) { events += event; order += event::class.simpleName.orEmpty() }
        override suspend fun commit(entries: List<SessionEntry>) { batches += entries; order += "commit" }
    }
    private fun raw(json: String) = ProtocolJson.parseToJsonElement(json).jsonObject

    @Test fun interleavingTypedToolsCommitBeforeCompletionAndNoEcho() = runBlocking {
        val sink = Recording()
        val state = ExecutionState("s", generation = "g", sink = sink)
        val reducer = AcpReducer(state)
        reducer.update(raw("""{"sessionUpdate":"state_update","state":"idle"}"""))
        assertTrue(sink.events.isEmpty())
        reducer.submitted()
        reducer.update(raw("""{"sessionUpdate":"user_message","messageId":"echo","content":[{"type":"text","text":"same"}]}"""))
        reducer.update(raw("""{"sessionUpdate":"agent_message_chunk","messageId":"m","content":{"type":"text","text":"one"}}"""))
        reducer.update(raw("""{"sessionUpdate":"agent_thought_chunk","messageId":"m","content":{"type":"text","text":"why"}}"""))
        reducer.update(raw("""{"sessionUpdate":"tool_call_update","toolCallId":"t","title":"Read","rawInput":{"path":"x"}}"""))
        reducer.update(raw("""{"sessionUpdate":"tool_call_update","toolCallId":"t","status":"completed","rawOutput":"done"}"""))
        reducer.update(raw("""{"sessionUpdate":"agent_message_chunk","messageId":"m","content":{"type":"text","text":" two"}}"""))
        assertTrue(sink.batches.isEmpty())
        reducer.update(raw("""{"sessionUpdate":"state_update","state":"idle","stopReason":"end_turn"}"""))
        val batch = sink.batches.single()
        assertEquals(listOf("assistant", "reasoning", "tool_call", "tool_result"), batch.map { it.identity()!!.type })
        assertEquals("one two", (batch.first() as SessionEntry.AssistantMessage).text)
        assertEquals("g:1:t", (batch[2] as SessionEntry.ToolCall).id)
        assertTrue(sink.order.indexOf("commit") < sink.order.indexOf("RunFinished"))
        assertTrue(state.live().entries.isEmpty())
        reducer.update(raw("""{"sessionUpdate":"agent_message","messageId":"m","content":[]}"""))
        assertEquals("unsupported_history_change", (sink.batches.last().single() as SessionEntry.Notice).code)
        reducer.submitted()
        reducer.update(raw("""{"sessionUpdate":"agent_message_chunk","messageId":"m","content":{"type":"text","text":"new"}}"""))
        assertEquals("g:2:m", state.live().entries.single().identity()!!.id)
    }

    @Test fun cancellationCommitsInterruptedDraftAndLossNeverFinishes() = runBlocking {
        val sink = Recording(); val state = ExecutionState("s", sink = sink)
        val reducer = AcpReducer(state); val broker = ExecutionBroker(state)
        reducer.submitted()
        reducer.update(raw("""{"sessionUpdate":"agent_message_chunk","messageId":"m","content":{"type":"text","text":"draft"}}"""))
        reducer.update(raw("""{"sessionUpdate":"state_update","state":"idle","stopReason":"cancelled"}"""))
        assertEquals(1, (sink.batches.single().single() as SessionEntry.Notice).draft.size)
        assertTrue(sink.events.none { it is AgentEvent.RunFinished })
        reducer.submitted()
        reducer.update(raw("""{"sessionUpdate":"agent_message_chunk","messageId":"n","content":{"type":"text","text":"lost"}}"""))
        reducer.lost(broker::lost)
        reducer.update(raw("""{"sessionUpdate":"state_update","state":"idle"}"""))
        assertEquals(ExecutionPhase.Lost, state.snapshot().phase)
        assertEquals(1, state.live().entries.size)
        assertEquals(1, sink.batches.size)
    }
    @Test fun unsupportedOrMismatchedMediaNeverReachesStorage() = runBlocking {
        var writes = 0
        val reducer = AcpReducer(ExecutionState("s", sink = Recording())) { data -> writes++; data.attachment }
        reducer.submitted()
        for (mime in listOf("image/png", "application/octet-stream")) {
            assertFailsWith<IllegalArgumentException> {
                reducer.update(raw("""{"sessionUpdate":"agent_message_chunk","messageId":"m","content":{"type":"image","mimeType":"$mime","data":"AA=="}}"""))
            }
        }
        assertEquals(0, writes)
    }

    @Test fun messageMediaChunksRetainAllLiveAndCommittedReferences() = runBlocking {
        val sink = Recording(); val state = ExecutionState("s", sink = sink)
        val saved = mutableListOf<Attachment>()
        val reducer = AcpReducer(state) { data -> data.attachment.also { saved += it } }
        val images = listOf(
            "image/png" to "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jP1sAAAAASUVORK5CYII=",
            "image/gif" to "R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7",
        )
        reducer.submitted()
        images.forEach { (mime, data) ->
            reducer.update(raw("""{"sessionUpdate":"agent_message_chunk","messageId":"m","content":{"type":"image","mimeType":"$mime","data":"$data"}}"""))
            assertEquals(saved, state.live().entries.filterIsInstance<SessionEntry.ToolResult>().single().attachments)
        }
        reducer.update(raw("""{"sessionUpdate":"agent_message_chunk","messageId":"m","content":{"type":"text","text":"Both images"}}"""))
        assertEquals(2, saved.size)
        assertEquals(saved, state.live().entries.filterIsInstance<SessionEntry.ToolResult>().single().attachments)
        reducer.update(raw("""{"sessionUpdate":"state_update","state":"idle","stopReason":"end_turn"}"""))
        assertEquals(saved, sink.batches.single().filterIsInstance<SessionEntry.ToolResult>().single().attachments)
    }

    @Test fun toolMediaUsesRuntimeStorageAndKeepsLocalReferences() = runBlocking {
        val sink = Recording(); val state = ExecutionState("s", sink = sink)
        val saved = mutableListOf<AttachmentData>()
        val reducer = AcpReducer(state) { data -> saved += data; data.attachment.copy(id = "stored") }
        reducer.submitted()
        reducer.update(raw("""{"sessionUpdate":"tool_call_update","toolCallId":"t","title":"Image","status":"completed","content":[{"type":"content","content":{"type":"image","mimeType":"image/png","data":"iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jP1sAAAAASUVORK5CYII="}}]}"""))
        reducer.update(raw("""{"sessionUpdate":"state_update","state":"idle","stopReason":"end_turn"}"""))
        assertEquals(1, saved.size)
        assertEquals("stored", sink.batches.single().filterIsInstance<SessionEntry.ToolResult>().single().attachments.single().id)
    }

}
