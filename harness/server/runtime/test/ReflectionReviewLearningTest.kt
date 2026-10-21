package kinuvo.harness.server

import ai.koog.prompt.streaming.StreamFrame
import kinuvo.harness.core.*
import kinuvo.harness.core.store.ReflectionConfig
import kinuvo.harness.core.tools.ToolNames
import kinuvo.harness.testing.*
import kinuvo.protocol.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class ReflectionReviewLearningTest {
    @Test
    fun oneOffReviewNotesUseReflectionModelAndKeepTwentyFeedbackEntries() = runTest {
        val configs = mutableListOf<AgentConfig>()
        val provider = object : ModelProvider {
            override val id = "test"
            override suspend fun models() = listOf(ModelInfo("tools"), ModelInfo("reflection"))
            override fun model(config: AgentConfig): AgentModel {
                configs += config
                return scriptedModel { prompt, tools ->
                    if (prompt.messages.toolResults.isEmpty()) flowOf(StreamFrame.ToolCallComplete("classification",
                        tools.single { "reflection_feedback" in it.name }.name, """{"lasting":false}"""), StreamFrame.End())
                    else flowOf(StreamFrame.End())
                }
            }
        }
        val fixture = RuntimeFixture(providers = listOf(provider))
        fixture.reflectionConfig = ReflectionConfig("test", "reflection")
        val harness = fixture.runtime()
        val agent = fixture.agent.copy(config = fixture.agent.config.copy(tools = listOf(ToolNames.SKILLS)))
        val session = harness.newSession(agent).id
        val proposals = (0..20).map { ReflectionProposal("p$it", "skill_manage", buildJsonObject {
            put("action", "create"); put("name", "release-$it"); put("content", "Unapplied")
        }, "Release", "user-0") }
        harness.log.append(agent, session, SessionEntry.Reflection("r", 0, ReflectionResult(proposals = proposals)))
        val review = ReflectionReview(harness)
        for (proposal in proposals) review.respond(agent, session, proposal.id, false, "wrong date ${proposal.id}")
        val decisions = harness.store.entries(agent, session).filterIsInstance<SessionEntry.ReflectionReview>()
        assertEquals(21, decisions.size)
        assertEquals("wrong date p20", decisions.last().note)
        assertTrue(decisions.all { it.result!!.proposals.isEmpty() })
        val feedback = Json.parseToJsonElement(harness.docs.read(agent.id, AgentDoc.ReflectionFeedback)).jsonArray
        assertEquals(20, feedback.size)
        assertEquals("p1", feedback.first().jsonObject.getValue("proposalId").jsonPrimitive.content)
        assertTrue(configs.all { it.model == "reflection" })
        review.respond(agent, session, "p20", false, "wrong date p20")
        assertEquals(21, configs.size)
        assertFailsWith<IllegalStateException> { review.respond(agent, session, "p20", false, "different") }
        harness.close()
    }
}
