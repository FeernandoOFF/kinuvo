package kinuvo.harness.core.tools

import kinuvo.harness.testing.*

import ai.koog.prompt.streaming.StreamFrame
import kinuvo.harness.core.PluginTools
import kinuvo.harness.core.ownedBy
import kinuvo.protocol.PluginInstanceId
import kinuvo.harness.core.AgentRun
import kinuvo.harness.core.ApprovalPolicy
import kinuvo.harness.core.Approvals
import kinuvo.harness.core.ApprovalBroker
import kinuvo.harness.core.Broker
import kinuvo.harness.core.TestHome
import kinuvo.protocol.AgentEvent
import kinuvo.protocol.QuestionKind
import kinuvo.protocol.SessionEntry
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AskUserToolTest {
    private val home = TestHome()
    private val tool = PluginTools(listOf(AskUserTool())).ownedBy(PluginInstanceId("kinuvo.core")).tools.single()
    private val form = """{"title":"Trip","questions":[
        {"id":"city","label":"Where?","kind":"single","options":["Rome","Oslo"]},
        {"id":"extras","label":"Extras","kind":"multi","options":["Bike","Car"],"default":["Bike"]},
        {"id":"note","label":"Anything else?","kind":"long_text"}]}"""

    private fun questions(json: String) = AskUserTool.questions(Json.parseToJsonElement(json).jsonObject)

    @Test
    fun parsesAndChecksTheForm() {
        val q = questions(form)
        assertEquals(listOf(QuestionKind.Single, QuestionKind.Multi, QuestionKind.LongText), q.map { it.kind })
        assertEquals(listOf("Bike"), q[1].default)
        assertFailsWith<IllegalArgumentException> { questions("""{"questions":[]}""") }
        assertFailsWith<IllegalArgumentException> { questions("""{"questions":[{"id":"a","label":"A","kind":"single"}]}""") }
        assertFailsWith<IllegalArgumentException> { questions("""{"questions":[{"id":"a","label":"A","kind":"multi","options":["x"],"default":["y"]}]}""") }
        assertFailsWith<IllegalArgumentException> { questions("""{"questions":[{"id":"a","label":"A"},{"id":"a","label":"B"}]}""") }
    }

    @Test
    fun resultKeepsListsForMultiAndDropsBlanks() {
        val q = questions(form)
        assertEquals("""{"city":"Oslo","extras":["Bike","Car"]}""", AskUserTool.result(q, mapOf("city" to listOf("Oslo"), "extras" to listOf("Bike", "Car"), "note" to listOf(""))))
        assertEquals("The user skipped the questions.", AskUserTool.result(q, null))
    }

    @Test fun formsStillHonorIdentityBasedDenyRules() = runTest {
        val agent = home.agent
        home.trustRules.update(agent.id) { it.copy(deny = kinuvo.protocol.DenyRules(tools = listOf(tool.identity!!.key))) }
        kotlin.test.assertIs<kinuvo.harness.core.Verdict.Block>(ApprovalPolicy(agent, home.trustRules).decide(tool, form))
    }

    @Test
    fun runWaitsForTheAnswersWithoutAnApproval() = runTest {
        val model = scriptedModel { prompt, _ ->
            val history = prompt.messages
            if (history.toolResults.isEmpty()) flowOf(StreamFrame.ToolCallComplete("c1", tool.descriptor.name, form), StreamFrame.End())
            else flowOf(StreamFrame.TextDelta("thanks"), StreamFrame.End())
        }
        val agent = home.agent.copy(config = home.agent.config.copy(approvals = "strict"))
        val questions = Broker<Map<String, List<String>>?>()
        val runner = AgentRun(agent, home.store, model, listOf(tool), approvals = Approvals(ApprovalPolicy(agent, home.trustRules), ApprovalBroker()), questions = questions)
        val session = home.store.createSession(agent, "").id
        val asked = Channel<AgentEvent>(Channel.UNLIMITED)
        val job = launch { runner.run(session, "plan a trip").collect { if (it is AgentEvent.QuestionsAsked || it is AgentEvent.ApprovalRequested) asked.send(it) } }
        val event = asked.receive() as AgentEvent.QuestionsAsked
        assertEquals("Trip", event.title)
        assertTrue(questions.respond(event.questionsId, mapOf("city" to listOf("Rome"))))
        job.join()
        assertEquals("""{"city":"Rome"}""", home.store.entries(agent, session).filterIsInstance<SessionEntry.ToolResult>().single().output)
    }
}
