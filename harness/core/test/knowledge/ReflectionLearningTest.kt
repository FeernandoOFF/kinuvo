package kinuvo.harness.core.knowledge

import ai.koog.prompt.message.Message
import ai.koog.prompt.streaming.StreamFrame
import kinuvo.harness.core.TestHome
import kinuvo.harness.core.modelAlias
import kinuvo.harness.core.skills.load
import kinuvo.harness.core.store.FileKnowledgeChangeLog
import kinuvo.harness.core.store.MarkdownKnowledgeSource
import kinuvo.harness.testing.*
import kinuvo.protocol.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.serialization.json.*
import kotlin.test.*

class ReflectionLearningTest {
    private val home = TestHome()
    private var conventions = "Keep project Notes."
    private val knowledge = Knowledge({ MarkdownKnowledgeSource(Path(home.files.home, "vault")) }, FileKnowledgeChangeLog(home.files.knowledgeChanges),
        conventions = { conventions }, updateConventions = { _, _, content -> conventions = content })
    private val reflection = Reflection(knowledge, home.docs, home.skills)
    private fun args(value: String) = Json.parseToJsonElement(value).jsonObject
    private fun proposal() = ReflectionProposal("p", "knowledge", args("""{"action":"create","type":"place","title":"Dublin"}"""),
        "A place", "User mentioned Dublin", scope = knowledge.scope())
    private fun alias(tool: String) = ToolIdentity(PluginInstanceId(if (tool in listOf("knowledge", "conventions")) "kinuvo.knowledge" else "kinuvo.core"), tool).modelAlias()
    private fun model(calls: List<Pair<String, JsonObject>>) = scriptedModel { prompt, tools ->
        if (prompt.messages.toolResults.isNotEmpty()) flowOf(StreamFrame.End()) else {
            val text = prompt.messages.filterIsInstance<Message.User>().single().textContent()
            assertFalse("Perform only the Memory stage" in text)
            assertTrue("review-note" in text)
            flowOf(*calls.mapIndexed { i, (tool, args) ->
                assertTrue(tools.any { it.name == alias(tool) })
                StreamFrame.ToolCallComplete("call-$i", alias(tool), args.toString())
            }.toTypedArray(), StreamFrame.End())
        }
    }
    private suspend fun review(proposal: ReflectionProposal, note: String, approve: Boolean, calls: List<Pair<String, JsonObject>>) =
        reflection.reflect(home.agent, model(calls), "s1", listOf(SessionEntry.UserMessage("Dublin is a city")),
            review = ReflectionRevision(proposal, note, approve))

    @Test
    fun lastingKnowledgeCorrectionStagesConventionsAndPreservesKnowledge() = runTest {
        val result = review(proposal(), "Don't make Notes for cities", false, listOf(
            "reflection_feedback" to args("""{"lasting":true}"""),
            "conventions" to args("""{"action":"edit","content":"Keep project Notes. Cities stay in their project Notes.","reason":"How the user organizes places","evidence":"review-note"}"""),
        ))
        val draft = result.proposals.single()
        assertEquals("conventions", draft.tool)
        assertEquals("Keep project Notes.", draft.conventionsBefore)
        assertEquals("Keep project Notes.", conventions)
        assertNull(knowledge.find("Dublin"))
        knowledge.saveConventionsReviewed(draft.scope!!, draft.conventionsBefore!!, draft.args.requireContent())
        assertEquals("Keep project Notes. Cities stay in their project Notes.", conventions)
    }

    @Test
    fun notedApprovalStagesBothRevisionAndLastingConventions() = runTest {
        val result = review(proposal(), "Keep cities in project Notes", true, listOf(
            "reflection_feedback" to args("""{"lasting":true}"""),
            "knowledge" to args("""{"action":"create","type":"project","title":"Visit Dublin","append":"Dublin is the destination","reason":"Keep the trip","evidence":"proposal review-note"}"""),
            "conventions" to args("""{"action":"edit","content":"Keep project Notes. Cities stay in project Notes.","reason":"Organize cities by project","evidence":"review-note"}"""),
        ))
        assertEquals(setOf("knowledge", "conventions"), result.proposals.map { it.tool }.toSet())
        assertNull(knowledge.find("Visit Dublin"))
        assertEquals("Keep project Notes.", conventions)
    }

    @Test
    fun conventionsCanBeRevisedWithAOneOffNote() = runTest {
        val proposal = proposal().copy(tool = "conventions", scope = knowledge.provider(), conventionsBefore = conventions,
            args = args("""{"action":"edit","content":"Project Notes."}"""))
        val result = review(proposal, "Keep the first sentence too", true, listOf(
            "reflection_feedback" to args("""{"lasting":false}"""),
            "conventions" to args("""{"action":"edit","content":"Keep project Notes. Cities stay in project Notes.","reason":"Preserve existing wording","evidence":"review-note"}"""),
        ))
        assertEquals("conventions", result.proposals.single().tool)
        assertEquals("Keep project Notes.", conventions)
    }

    @Test
    fun lastingSkillCorrectionUsesTheReviewedSkill() = runTest {
        val before = "---\nname: release\ndescription: Release checks\n---\nRun checks.\n"
        home.skills.save(home.agent, ConfigScope.Agent, "release", before)
        val content = before + "Check signing before publishing.\n"
        val proposal = ReflectionProposal("skill", "skill_manage", buildJsonObject {
            put("action", "edit"); put("name", "release"); put("content", before + "Publish.\n")
        }, "Release flow", "Successful checks", skillBefore = before)
        val result = review(proposal, "Always check signing first", false, listOf(
            "reflection_feedback" to args("""{"lasting":true}"""),
            "skill_manage" to buildJsonObject {
                put("action", "edit"); put("name", "release"); put("content", content)
                put("reason", "Signing is a prerequisite"); put("evidence", "review-note")
            },
        ))
        assertEquals(before, home.skills.content(home.skills.load(home.agent).single { it.name == "release" }))
        assertEquals(content, result.proposals.single().args.requireContent())
        assertEquals(before, result.proposals.single().skillBefore)
    }

    @Test
    fun oneOffRejectionProducesNoDraftsAndMissingClassificationFails() = runTest {
        assertTrue(review(proposal(), "wrong date", false, listOf("reflection_feedback" to args("""{"lasting":false}"""))).proposals.isEmpty())
        assertFailsWith<IllegalStateException> { review(proposal(), "wrong date", true, emptyList()) }
    }

    @Test
    fun rejectedKnowledgeCannotBeReproposedAndStaleConventionsCannotBeOverwritten() = runTest {
        assertFailsWith<IllegalStateException> {
            review(proposal(), "Don't make city Notes", false, listOf(
                "reflection_feedback" to args("""{"lasting":true}"""),
                "knowledge" to args("""{"action":"create","type":"place","title":"Dublin","reason":"Place","evidence":"review-note"}"""),
            ))
        }
        assertNull(knowledge.find("Dublin"))
        assertFailsWith<IllegalStateException> { knowledge.saveConventionsReviewed(knowledge.provider(), "Old", "Replacement") }
        assertFailsWith<IllegalStateException> { knowledge.saveConventionsReviewed("other", conventions, "Replacement") }
        assertEquals("Keep project Notes.", conventions)
    }

    private fun JsonObject.requireContent() = getValue("content").jsonPrimitive.content
}
