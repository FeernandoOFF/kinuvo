package kinuvo.app.workflow

import kinuvo.protocol.agent.AgentId
import kinuvo.protocol.workflow.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A starting graph for a new Workflow; [build] makes it run as the given Agent. */
class WorkflowTemplate(val name: String, val summary: String, val build: (WorkflowId, AgentId) -> Workflow)

object WorkflowTemplates {
    private fun edge(from: String, to: String, branch: Boolean? = null) = WorkflowEdge(StepId(from), StepId(to), branch)
    private fun at(column: Int, row: Int = 0) = Position(40f + column * 340f, 80f + row * 300f)
    private const val JSON_ONLY = "Reply with one JSON object and nothing else."

    val all = listOf(
        WorkflowTemplate("Extract, review, fill", "A small model pulls fields out of text, you correct them in a form, and a script fills a template. No chat until the Review.") { id, agent ->
            Workflow(id, "Extract, review, fill", listOf(
                WorkflowStep.Model(StepId("model-1"), "From this message extract customer (full name), order (order number), issue (one sentence) and urgent (true or false):\n\n{{input.message}}",
                    "You extract fields from customer messages. $JSON_ONLY", position = at(0)),
                WorkflowStep.Review(StepId("review-1"), "Check the extracted fields", "{{steps.model-1.output}}", at(1)),
                WorkflowStep.Tool(StepId("tool-1"), "run_code", buildJsonObject {
                    put("input", "{{steps.review-1.output}}")
                    put("code", "const urgent = input.urgent ? ' We are treating this as urgent.' : ''\nreturn `Hi \${input.customer},\\n\\nThank you for getting in touch about order \${input.order}. \${input.issue}\${urgent}\\n\\nBest wishes,\\nSupport`")
                }, at(2)),
            ), listOf(edge("model-1", "review-1"), edge("review-1", "tool-1")), agentId = agent,
                description = "Turns a customer message into a reply draft, with the extracted fields checked by you.",
                inputs = listOf(WorkflowInput("message", "The message to answer", "Hello, I am Dana Whitfield. Order A-10492 arrived with a cracked screen and I need it sorted today.")))
        },
        WorkflowTemplate("Fetch, summarize, hand over", "Fetch a page without a model, have a small model summarize it, approve it, then an Agent picks it up in a chat.") { id, agent ->
            Workflow(id, "Fetch, summarize, hand over", listOf(
                WorkflowStep.Tool(StepId("tool-1"), "web_fetch", buildJsonObject { put("url", "{{input.url}}") }, at(0)),
                WorkflowStep.Model(StepId("model-1"), "Summarize this page in five bullet points:\n\n{{steps.tool-1.output}}", "You write short, factual summaries.", position = at(1)),
                WorkflowStep.Review(StepId("review-1"), "Edit the summary", "{{steps.model-1.output}}", at(2)),
                WorkflowStep.AgentTurn(StepId("agent-1"), agent, "Here is a summary I approved. Tell me what, if anything, I should act on:\n\n{{steps.review-1.output}}", position = at(3)),
            ), listOf(edge("tool-1", "model-1"), edge("model-1", "review-1"), edge("review-1", "agent-1")), agentId = agent,
                description = "Summarizes a web page and hands the approved summary to an Agent.",
                inputs = listOf(WorkflowInput("url", "The page to read", "https://example.com")))
        },
        WorkflowTemplate("Triage with a condition", "A small model classifies a message, and a Condition sends urgent ones to you for Approval while the rest are filed by a script.") { id, agent ->
            Workflow(id, "Triage with a condition", listOf(
                WorkflowStep.Model(StepId("model-1"), "Classify this message. Reply with urgent (true or false) and reason (one sentence):\n\n{{input.message}}",
                    "You triage incoming messages. $JSON_ONLY", position = at(0)),
                WorkflowStep.Condition(StepId("condition-1"), "{{steps.model-1.output.urgent}}", ConditionOp.Equals, "true", at(1)),
                WorkflowStep.Approval(StepId("approval-1"), "This message looks urgent. Handle it now?", at(2)),
                WorkflowStep.AgentTurn(StepId("agent-1"), agent, "This message is urgent ({{steps.model-1.output.reason}}). Draft a reply:\n\n{{input.message}}", position = at(3)),
                WorkflowStep.Tool(StepId("tool-1"), "run_code", buildJsonObject {
                    put("input", "{{steps.model-1.output}}")
                    put("code", "return 'Filed, not urgent: ' + input.reason")
                }, at(2, 1)),
            ), listOf(edge("model-1", "condition-1"), edge("condition-1", "approval-1", true), edge("approval-1", "agent-1"), edge("condition-1", "tool-1", false)), agentId = agent,
                description = "Sends urgent messages to you and files the rest.",
                inputs = listOf(WorkflowInput("message", "The message to triage", "The production database is down and customers cannot log in.")))
        },
    )
}
