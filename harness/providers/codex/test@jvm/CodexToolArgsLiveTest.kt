package kinuvo.harness.codex

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.openai.OpenAIResponsesParams
import ai.koog.prompt.executor.clients.openai.base.models.ReasoningEffort
import ai.koog.prompt.executor.clients.openai.models.ReasoningConfig
import ai.koog.prompt.streaming.StreamFrame
import kinuvo.harness.core.tools.RawSchemaToolDescriptor
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test

class CodexToolArgsLiveTest {
    @Test
    fun leavesOptionalArgumentsOut() {
        if (System.getenv("KINUVO_LIVE") != "1") return
        val schema = Json.parseToJsonElement(
            """{"properties":{"from_date":{"type":"string"},"to_date":{"type":"string"},"project_id":{"type":"string","description":"Optional project ID filter"}},"required":["from_date","to_date"]}"""
        ).jsonObject
        val tool = RawSchemaToolDescriptor("freeagent__timesheet_summary", "Summarize timesheets for a date range.", schema)
        val params = OpenAIResponsesParams(
            store = false,
            reasoning = ReasoningConfig(effort = ReasoningEffort.LOW),
            additionalProperties = mapOf("instructions" to JsonPrimitive("Use tools when asked.")),
        )
        val p = prompt("live", params) { user("Summarize my timesheets from 2026-09-28 to 2026-10-02 across all projects.") }
        runBlocking {
            codexLLMClient(CodexAuth()).executeStreaming(p, codexModel(System.getenv("KINUVO_MODEL") ?: "gpt-5.6-sol"), listOf(tool)).collect {
                if (it is StreamFrame.ToolCallComplete) println("TOOL CALL: ${it.content}")
            }
        }
    }
}
