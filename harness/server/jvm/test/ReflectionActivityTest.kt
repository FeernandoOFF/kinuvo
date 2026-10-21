package kinuvo.harness.server

import kinuvo.harness.testing.*
import ai.koog.prompt.message.Message

import kinuvo.harness.core.modelAlias
import ai.koog.prompt.streaming.StreamFrame
import kinuvo.harness.codex.CodexAuth
import kinuvo.harness.core.PrivateFile
import kinuvo.harness.codex.CodexProvider
import kinuvo.harness.core.store.FileStore
import kinuvo.protocol.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ReflectionActivityTest {
    @Test
    fun publishesLiveActivityAndPersistsCompletedEmptyAndFailedPasses() = runBlocking {
        val home = Files.createTempDirectory("reflection-activity").toFile()
        var outcome = "saved"
        lateinit var harness: Harness
        var sessionId = ""
        val model = scriptedModel { prompt, _ ->
            val instructions = prompt.messages.filterIsInstance<Message.System>().firstOrNull()?.textContent().orEmpty()
            val history = prompt.messages
             flow {
            val agent = harness.store.agents().single()
            val caught = mutableListOf<SessionUpdate>()
            val subscriber = SessionLog.Subscriber { caught += it; true }
            harness.log.subscribe(agent, sessionId, null, 100, subscriber)
            assertNotNull(caught.map { it.change }.filterIsInstance<SessionChange.Live>().single().state.reflectionId)
            assertEquals(SessionStatus.Idle, harness.log.status(sessionId))
            harness.log.unsubscribeAll(subscriber)
            assertTrue("People go in person notes" in history.first { it is Message.User && it.textContent().startsWith("Background Reflection instructions:") }.textContent())
            emitAll(when {
                outcome == "failed" -> error("Provider unavailable")
                outcome == "empty" || history.toolResults.isNotEmpty() -> flowOf(StreamFrame.TextDelta("Finished."), StreamFrame.End())
                else -> flowOf(
                    StreamFrame.ToolCallComplete("c1", kinuvo.protocol.ToolIdentity(kinuvo.protocol.PluginInstanceId("kinuvo.knowledge"), "knowledge").modelAlias(), """{"action":"create","type":"person","title":"Ana","reason":"Family","evidence":"user-1: sister Ana"}"""),
                    StreamFrame.ToolCallComplete("c2", kinuvo.protocol.ToolIdentity(kinuvo.protocol.PluginInstanceId("kinuvo.core"), "memory").modelAlias(), """{"action":"add","target":"memory","content":"Use examples"}"""),
                    StreamFrame.ToolCallComplete("c3", kinuvo.protocol.ToolIdentity(kinuvo.protocol.PluginInstanceId("kinuvo.core"), "memory").modelAlias(), """{"action":"add","target":"user","content":"Lives in Dublin"}"""),
                    StreamFrame.End(),
                )
            })
        } }
        harness = Harness(home = Path(home.path), providers = listOf(CodexProvider(CodexAuth(PrivateFile(Path(home.path, "none.json"))))), modelFactory = { model }, plugins = emptyList())
        harness.saveKnowledgeSettings(KnowledgeSettings(conventions = mapOf("markdown" to "People go in person notes")))
        val agent = harness.store.agents().single()
        sessionId = harness.newSession(agent).id
        val reflector = Reflector(harness, CoroutineScope(Dispatchers.Default), busy = { false })
        val updates = mutableListOf<SessionUpdate>()
        val subscriber = SessionLog.Subscriber { updates += it; true }
        harness.log.subscribe(agent, sessionId, null, 100, subscriber)
        harness.store.append(agent, sessionId, SessionEntry.UserMessage("My sister Ana lives in Dublin. Examples help."))
        assertEquals(2, reflector.reflect(agent, sessionId))
        assertEquals(0, reflector.reflect(agent, sessionId))
        val saved = harness.store.entries(agent, sessionId).filterIsInstance<SessionEntry.Reflection>().single()
        assertEquals(ReflectionResult(memory = 1, userNotes = 1, proposals = saved.result.proposals, measurements = saved.result.measurements,
            memoryChanges = listOf(ReflectionMemoryChange("memory", "", "- Use examples\n"), ReflectionMemoryChange("user", "", "- Lives in Dublin\n"))), saved.result)
        val proposal = saved.result.proposals.single()
        assertEquals(null, harness.knowledge.find("Ana"))
        val review = ReflectionReview(harness)
        review.respond(agent, sessionId, proposal.id, true)
        review.respond(agent, sessionId, proposal.id, true)
        assertEquals("Ana", harness.knowledge.find("Ana")?.title)
        assertEquals(1, harness.knowledge.changes().size)
        assertEquals(1, FileStore(harness.files).entries(agent, sessionId).filterIsInstance<SessionEntry.ReflectionReview>().size)
        assertTrue(updates.any { (it.change as? SessionChange.Event)?.event == AgentEvent.ReflectionStarted(saved.id) })
        assertTrue(updates.any { (it.change as? SessionChange.Entry)?.entry == saved })
        assertEquals(saved, FileStore(harness.files).entries(agent, sessionId).filterIsInstance<SessionEntry.Reflection>().single())

        outcome = "empty"
        harness.store.append(agent, sessionId, SessionEntry.UserMessage("Thanks"))
        assertEquals(0, reflector.reflect(agent, sessionId))
        outcome = "failed"
        harness.store.append(agent, sessionId, SessionEntry.UserMessage("Something else"))
        assertEquals(0, reflector.reflect(agent, sessionId))
        val results = harness.store.entries(agent, sessionId).filterIsInstance<SessionEntry.Reflection>()
        assertEquals(3, results.size)
        assertEquals(ReflectionResult(), results[1].result.copy(measurements = emptyList()))
        assertEquals("Provider unavailable", results[2].result.error)
        val caught = mutableListOf<SessionUpdate>()
        harness.log.subscribe(agent, sessionId, null, 100, SessionLog.Subscriber { caught += it; true })
        assertEquals(null, caught.map { it.change }.filterIsInstance<SessionChange.Live>().single().state.reflectionId)
        assertEquals(SessionStatus.Idle, harness.log.status(sessionId))
    }
}
