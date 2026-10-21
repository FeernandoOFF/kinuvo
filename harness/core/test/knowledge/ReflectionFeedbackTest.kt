package kinuvo.harness.core.knowledge

import kinuvo.harness.core.AgentDoc
import kinuvo.harness.core.TestHome
import kinuvo.harness.testing.*
import ai.koog.prompt.message.Message
import ai.koog.prompt.streaming.StreamFrame
import kinuvo.protocol.ReflectionProposal
import kinuvo.protocol.SessionEntry
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.*

class ReflectionFeedbackTest {
    @Test
    fun feedbackKeepsTwentyAndOnlyReachesItsStage() = runTest {
        val home = TestHome()
        for (i in 0..20) home.docs.rememberReflectionReview(home.agent.id,
            ReflectionProposal("p$i", "knowledge", JsonObject(mapOf("title" to JsonPrimitive("Proposal $i"))), "Reason", "Source"), "Correction $i", true)
        home.docs.rememberReflectionReview(home.agent.id,
            ReflectionProposal("skill", "skill_manage", JsonObject(emptyMap()), "Reason", "Source"), "Skill correction", false)
        val stored = home.docs.read(home.agent.id, AgentDoc.ReflectionFeedback)
        assertEquals(20, Regex("proposalId").findAll(stored).count())
        assertFalse("\"p1\"" in stored)
        val model = scriptedModel { prompt, _ ->
            val text = prompt.messages.filterIsInstance<Message.User>().single().textContent()
            val stage = ReflectionStage.entries.single { "Perform only the ${it.name} stage" in text }
            assertEquals(stage == ReflectionStage.Knowledge, "Correction 20" in text)
            assertEquals(stage == ReflectionStage.Skills, "Skill correction" in text)
            flowOf(StreamFrame.End())
        }
        val knowledge = Knowledge({ kinuvo.harness.core.store.MarkdownKnowledgeSource(kotlinx.io.files.Path(home.files.home, "vault")) },
            kinuvo.harness.core.store.FileKnowledgeChangeLog(home.files.knowledgeChanges))
        assertNull(Reflection(knowledge, home.docs, home.skills).reflect(home.agent, model, "s1", listOf(SessionEntry.UserMessage("Review"))).error)
    }
}
