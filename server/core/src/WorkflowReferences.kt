package kinuvo.server

import kinuvo.protocol.workflow.StepId
import kinuvo.protocol.workflow.WorkflowStep
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** `{{steps.<id>.output}}` and `{{steps.<id>.output.<key or index>...}}` in a step's text, resolved against earlier steps' outputs. */
internal object WorkflowReferences {
    private val pattern = Regex("\\{\\{steps\\.([a-zA-Z0-9_-]+)\\.output((?:\\.[^.{}\\s]+)*)}}")

    fun strings(step: WorkflowStep): List<String> = when (step) {
        is WorkflowStep.AgentTurn -> listOf(step.prompt)
        is WorkflowStep.Model -> listOfNotNull(step.prompt, step.system)
        is WorkflowStep.Tool -> strings(step.args)
        is WorkflowStep.Approval -> emptyList()
    }

    private fun strings(element: JsonElement): List<String> = when (element) {
        is JsonPrimitive -> listOfNotNull(element.takeIf { it.isString }?.content)
        is JsonArray -> element.flatMap(::strings)
        is JsonObject -> element.values.flatMap(::strings)
    }

    fun ids(text: String) = pattern.findAll(text).map { StepId(it.groupValues[1]) }.toList()

    fun render(text: String, outputs: (StepId) -> String?): String =
        pattern.replace(text) { match -> if (match.groupValues[2].isEmpty()) outputs(StepId(match.groupValues[1])).orEmpty() else asText(resolve(match, outputs)) }

    fun render(args: JsonObject, outputs: (StepId) -> String?): JsonObject = JsonObject(args.mapValues { (_, v) -> render(v, outputs) })

    private fun render(element: JsonElement, outputs: (StepId) -> String?): JsonElement = when (element) {
        is JsonArray -> JsonArray(element.map { render(it, outputs) })
        is JsonObject -> render(element, outputs)
        is JsonPrimitive -> if (!element.isString) element else pattern.matchEntire(element.content)?.let { resolve(it, outputs) }
            ?: JsonPrimitive(render(element.content, outputs))
    }

    private fun asText(element: JsonElement) = if (element is JsonPrimitive && element.isString) element.content else element.toString()

    private fun resolve(match: MatchResult, outputs: (StepId) -> String?): JsonElement {
        val output = outputs(StepId(match.groupValues[1])).orEmpty()
        val path = match.groupValues[2].split('.').filter { it.isNotEmpty() }
        val parsed = try { Json.parseToJsonElement(output) } catch (_: IllegalArgumentException) { null }
        if (path.isEmpty()) return parsed ?: JsonPrimitive(output)
        var at = parsed ?: error("${match.value}: step output is not JSON")
        for (segment in path) {
            at = when (at) {
                is JsonObject -> at[segment]
                is JsonArray -> segment.toIntOrNull()?.let { at.getOrNull(it) }
                else -> null
            } ?: error("${match.value}: no '$segment' in the step output")
        }
        return at
    }
}
