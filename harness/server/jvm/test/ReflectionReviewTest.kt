package kinuvo.harness.server

import kinuvo.harness.testing.*

import kinuvo.harness.codex.CodexAuth
import kinuvo.harness.core.modelAlias
import kinuvo.harness.core.PrivateFile
import kinuvo.harness.codex.CodexProvider
import kinuvo.harness.core.knowledge.ChangeSource
import kinuvo.harness.core.knowledge.KnowledgeTool
import kinuvo.harness.core.skills.load
import kinuvo.harness.core.store.FileStore
import kinuvo.client.KinuvoClient
import kinuvo.protocol.*
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.io.files.Path
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ReflectionReviewTest {
    private val home = Files.createTempDirectory("reflection-review").toFile()
    private fun harness(model: kinuvo.harness.core.AgentModel = scriptedModel { _, _ -> emptyFlow() }) = Harness(home = Path(home.path), providers = listOf(CodexProvider(CodexAuth(PrivateFile(Path(home.path, "none.json"))))),
        modelFactory = { model }, plugins = emptyList())

    @Test
    fun notedApprovalRevisesWithoutWritingAndPersistsAcrossRestart() = runBlocking {
        var requests = 0
        val model = scriptedModel { prompt, tools ->
            requests++
            if (prompt.messages.toolResults.isNotEmpty()) kotlinx.coroutines.flow.flowOf(ai.koog.prompt.streaming.StreamFrame.End())
            else {
                val text = prompt.messages.filterIsInstance<ai.koog.prompt.message.Message.User>().single().textContent()
                assertTrue("Perform only the Knowledge stage" in text)
                assertTrue("wrong date" in text)
                kotlinx.coroutines.flow.flowOf(ai.koog.prompt.streaming.StreamFrame.ToolCallComplete("revision", tools.single { it.name == ToolIdentity(PluginInstanceId("kinuvo.knowledge"), "knowledge").modelAlias() }.name,
                    """{"action":"create","type":"project","title":"Release","append":"Friday","reason":"Correct date","evidence":"review-note proposal"}"""),
                    ai.koog.prompt.streaming.StreamFrame.ToolCallComplete("classify", tools.single { "reflection_feedback" in it.name }.name, """{"lasting":false}"""),
                    ai.koog.prompt.streaming.StreamFrame.End())
            }
        }
        val harness = harness(model)
        val agent = harness.store.agents().single()
        val session = harness.newSession(agent).id
        val proposal = ReflectionProposal("dated", "knowledge", Json.parseToJsonElement("""{"action":"create","type":"project","title":"Release","append":"Thursday"}""").jsonObject,
            "Schedule", "User: release date", scope = harness.knowledge.scope())
        harness.log.append(agent, session, SessionEntry.Reflection("dated", 0, ReflectionResult(proposals = listOf(proposal))))
        val review = ReflectionReview(harness)
        review.respond(agent, session, proposal.id, true, "  wrong date  ")
        assertEquals(null, harness.knowledge.find("Release"))
        val saved = harness.store.entries(agent, session).filterIsInstance<SessionEntry.ReflectionReview>().single()
        assertEquals("wrong date", saved.note)
        val revised = saved.result!!.proposals.single()
        assertEquals("Friday", revised.args["append"]!!.toString().trim('"'))
        val previous = requests
        review.respond(agent, session, proposal.id, true, "wrong date")
        assertEquals(previous, requests)
        val restarted = harness()
        ReflectionReview(restarted).respond(agent, session, revised.id, true)
        assertTrue("Friday" in restarted.knowledge.find("Release")!!.body)
    }

    @Test
    fun lastingKnowledgeNoteProposesConventionsThatNeedApproval() = runBlocking {
        val model = scriptedModel { prompt, tools ->
            if (prompt.messages.toolResults.isNotEmpty()) kotlinx.coroutines.flow.flowOf(ai.koog.prompt.streaming.StreamFrame.End())
            else kotlinx.coroutines.flow.flowOf(
                ai.koog.prompt.streaming.StreamFrame.ToolCallComplete("classify", tools.single { "reflection_feedback" in it.name }.name, """{"lasting":true}"""),
                ai.koog.prompt.streaming.StreamFrame.ToolCallComplete("learning", tools.single { "conventions" in it.name }.name,
                    """{"action":"edit","content":"Cities stay in project Notes.","reason":"How the user organizes cities","evidence":"review-note"}"""),
                ai.koog.prompt.streaming.StreamFrame.End(),
            )
        }
        val harness = harness(model)
        val agent = harness.store.agents().single()
        val session = harness.newSession(agent).id
        val proposal = ReflectionProposal("city", "knowledge", Json.parseToJsonElement("""{"action":"create","type":"place","title":"Dublin"}""").jsonObject,
            "A place", "User: Dublin", scope = harness.knowledge.scope())
        harness.log.append(agent, session, SessionEntry.Reflection("city", 0, ReflectionResult(proposals = listOf(proposal))))
        ReflectionReview(harness).respond(agent, session, "city", false, "Don't make Notes for cities")
        val pending = harness.store.entries(agent, session).filterIsInstance<SessionEntry.ReflectionReview>().single().result!!.proposals.single()
        assertEquals("conventions", pending.tool)
        assertEquals("", harness.knowledge.conventions())
        assertEquals(null, harness.knowledge.find("Dublin"))
        val restarted = harness()
        ReflectionReview(restarted).respond(agent, session, pending.id, true)
        assertEquals("Cities stay in project Notes.", restarted.knowledge.conventions())
        assertEquals("Cities stay in project Notes.", restarted.files.conventions("markdown").let { java.io.File(it.toString()).readText() })
    }

    @Test
    fun reviewsKnowledgeAcrossRestartAndRefusesChangedTargets() = runBlocking {
        val original = harness()
        val agent = original.store.agents().single()
        val session = original.newSession(agent).id
        val note = original.knowledge.create("project", "Kinuvo", body = "Original")
        val proposal = ReflectionProposal("p1", "knowledge", Json.parseToJsonElement("""{"action":"update","ref":"${note.ref}","append":"Accepted decision"}""").jsonObject,
            "Project decision", "user-0", note = note, scope = original.knowledge.scope())
        original.log.append(agent, session, SessionEntry.Reflection("r1", 0, ReflectionResult(proposals = listOf(proposal))))
        val restarted = harness()
        val review = ReflectionReview(restarted)
        restarted.knowledge.update(note.ref, body = listOf(kinuvo.harness.core.knowledge.BodyEdit.Append("Concurrent edit")))
        assertFailsWith<IllegalStateException> { review.respond(agent, session, proposal.id, true) }
        assertTrue("Accepted decision" !in restarted.knowledge.find(note.ref)!!.body)
        review.respond(agent, session, proposal.id, false)
        review.respond(agent, session, proposal.id, false)
        assertFailsWith<IllegalStateException> { review.respond(agent, session, proposal.id, true) }
        assertEquals(listOf(SessionEntry.ReflectionReview("p1", false)), FileStore(restarted.files).entries(agent, session).filterIsInstance<SessionEntry.ReflectionReview>())

        val create = proposal.copy(id = "p2", note = null, args = Json.parseToJsonElement("""{"action":"create","type":"person","title":"Ana"}""").jsonObject)
        restarted.log.append(agent, session, SessionEntry.Reflection("r2", 0, ReflectionResult(proposals = listOf(create))))
        KnowledgeTool(restarted.knowledge, ChangeSource(agent.id, session, create.id)).executeReviewed(create.args, null, create.scope!!)
        // A restart between applying the change and persisting its decision must not apply it twice.
        val afterApply = harness()
        ReflectionReview(afterApply).respond(agent, session, create.id, true)
        assertEquals(1, afterApply.knowledge.changes().count { it.id == create.id })
        assertEquals("Ana", afterApply.knowledge.find("Ana")?.title)
    }

    @Test
    fun clientApprovesSkillDraftAndStaleDraftDoesNotOverwriteSkill() = runBlocking {
        val harness = harness()
        val agent = harness.store.agents().single()
        val session = harness.newSession(agent).id
        val content = "---\nname: release-check\ndescription: Verify a release\n---\nRun release checks.\n"
        val args = kotlinx.serialization.json.buildJsonObject {
            put("action", kotlinx.serialization.json.JsonPrimitive("create"))
            put("name", kotlinx.serialization.json.JsonPrimitive("release-check"))
            put("content", kotlinx.serialization.json.JsonPrimitive(content))
        }
        val proposal = ReflectionProposal("skill-1", "skill_manage", args, "Verified release workflow", "tool-1")
        harness.log.append(agent, session, SessionEntry.Reflection("r1", 0, ReflectionResult(proposals = listOf(proposal))))
        assertTrue(harness.skills.load(agent).none { it.name == "release-check" })
        val server = HarnessServer(harness)
        val port = server.start("127.0.0.1", 0)
        val client = KinuvoClient("ws://127.0.0.1:$port/ws", "test", pairingCode = server.auth.currentPairingCode())
        try {
            client.connect()
            val params = ReviewReflectionParams(agent.id, session, proposal.id, true)
            client.call(Api.ReviewReflection, params)
            client.call(Api.ReviewReflection, params)
            assertEquals(content, harness.skills.content(harness.skills.load(agent).single { it.name == "release-check" }))
            val edit = proposal.copy(id = "skill-2", skillBefore = content,
                args = kotlinx.serialization.json.JsonObject(args + ("content" to kotlinx.serialization.json.JsonPrimitive(content + "New lesson\n")) +
                    ("action" to kotlinx.serialization.json.JsonPrimitive("edit"))))
            harness.log.append(agent, session, SessionEntry.Reflection("r2", 0, ReflectionResult(proposals = listOf(edit))))
            harness.skills.save(agent, ConfigScope.Agent, "release-check", content + "User edit\n")
            assertFailsWith<IllegalStateException> { client.call(Api.ReviewReflection, params.copy(proposalId = edit.id)) }
            assertEquals(content + "User edit\n", harness.skills.content(harness.skills.load(agent).single { it.name == "release-check" }))
        } finally {
            client.close()
            server.stop()
        }
    }
}
