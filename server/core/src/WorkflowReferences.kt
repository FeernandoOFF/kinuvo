package kinuvo.server

import kinuvo.protocol.workflow.ConditionOp
import kinuvo.protocol.workflow.StepId
import kinuvo.protocol.workflow.WorkflowStep
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/** What a run's references read: each earlier step's output, null when it has none, and the run's input. */
internal class WorkflowValues(val outputs: (StepId) -> String?, val input: JsonObject = JsonObject(emptyMap()))

/**
 * `{{steps.<id>.output}}`, `{{steps.<id>.output.<key or index>...}}`, `{{input}}` and `{{input.<name>...}}` in a step's text,
 * resolved against earlier steps' outputs and the run's input.
 */
internal object WorkflowReferences {
    private val pattern = Regex("\\{\\{(?:steps\\.([a-zA-Z0-9_-]+)\\.output|(input))((?:\\.[^.{}\\s]+)*)}}")
    private val fence = Regex("^```[a-zA-Z0-9]*[ \\t]*\\r?\\n(.*?)\\r?\\n?```$", RegexOption.DOT_MATCHES_ALL)

    fun strings(step: WorkflowStep): List<String> = when (step) {
        is WorkflowStep.AgentTurn -> listOf(step.prompt)
        is WorkflowStep.Model -> listOfNotNull(step.prompt, step.system)
        is WorkflowStep.Tool -> strings(step.args)
        is WorkflowStep.Approval -> emptyList()
        is WorkflowStep.Review -> listOf(step.value)
        is WorkflowStep.Condition -> listOf(step.left, step.right)
    }

    private fun strings(element: JsonElement): List<String> = when (element) {
        is JsonPrimitive -> listOfNotNull(element.takeIf { it.isString }?.content)
        is JsonArray -> element.flatMap(::strings)
        is JsonObject -> element.values.flatMap(::strings)
    }

    /** The steps [text] reads the output of. */
    fun ids(text: String) = pattern.findAll(text).map { it.groupValues[1] }.filter { it.isNotEmpty() }.map(::StepId).toList()

    /** [text] as JSON, also when a model wrapped it in one Markdown code fence; null when it is neither. */
    fun json(text: String): JsonElement? {
        // The parser reads a bare word as a literal, which is text here, not JSON.
        fun parse(s: String) = try { Json.parseToJsonElement(s) } catch (_: IllegalArgumentException) { null }
            ?.takeUnless { it is JsonPrimitive && it !is JsonNull && !it.isString && it.booleanOrNull == null && it.doubleOrNull == null }
        return parse(text) ?: fence.matchEntire(text.trim())?.let { parse(it.groupValues[1]) }
    }

    fun render(text: String, values: WorkflowValues): String = pattern.replace(text) { match ->
        if (match.groupValues[1].isNotEmpty() && match.groupValues[3].isEmpty()) values.outputs(StepId(match.groupValues[1])).orEmpty() else asText(resolve(match, values))
    }

    fun render(args: JsonObject, values: WorkflowValues): JsonObject = JsonObject(args.mapValues { (_, v) -> render(v, values) })

    private fun render(element: JsonElement, values: WorkflowValues): JsonElement = when (element) {
        is JsonArray -> JsonArray(element.map { render(it, values) })
        is JsonObject -> render(element, values)
        is JsonPrimitive -> if (!element.isString) element else pattern.matchEntire(element.content)?.let { resolve(it, values) }
            ?: JsonPrimitive(render(element.content, values))
    }

    private fun asText(element: JsonElement) = when {
        element is JsonNull -> ""
        element is JsonPrimitive && element.isString -> element.content
        else -> element.toString()
    }

    /** A missing input, or anything read from a step without an output such as a skipped one, is null; a path a step's output lacks fails the step. */
    private fun resolve(match: MatchResult, values: WorkflowValues): JsonElement {
        val path = match.groupValues[3].split('.').filter { it.isNotEmpty() }
        val step = match.groupValues[1]
        var at: JsonElement = if (step.isEmpty()) values.input else {
            val output = values.outputs(StepId(step)) ?: return JsonNull
            json(output) ?: if (path.isEmpty()) return JsonPrimitive(output) else error("${match.value}: step output is not JSON")
        }
        for (segment in path) {
            at = when (val current = at) {
                is JsonObject -> current[segment]
                is JsonArray -> segment.toIntOrNull()?.let { current.getOrNull(it) }
                else -> null
            } ?: if (step.isEmpty()) return JsonNull else error("${match.value}: no '$segment' in the step output")
        }
        return at
    }

    /** Whether a Condition holds for its rendered sides. */
    fun test(left: String, op: ConditionOp, right: String): Boolean {
        val l = left.trim()
        val r = right.trim()
        fun numbers() = (l.toDoubleOrNull() ?: error("Condition needs numbers, but the left side is '${l.take(80)}'")) to
            (r.toDoubleOrNull() ?: error("Condition needs numbers, but the right side is '${r.take(80)}'"))
        fun equal() = l == r || (l.toDoubleOrNull()?.let { it == r.toDoubleOrNull() } ?: false)
        fun empty() = l.isEmpty() || l in setOf("null", "[]", "{}", "false")
        return when (op) {
            ConditionOp.Equals -> equal()
            ConditionOp.NotEquals -> !equal()
            ConditionOp.Contains -> r in l
            ConditionOp.NotContains -> r !in l
            ConditionOp.Empty -> empty()
            ConditionOp.NotEmpty -> !empty()
            ConditionOp.GreaterThan -> numbers().let { (a, b) -> a > b }
            ConditionOp.LessThan -> numbers().let { (a, b) -> a < b }
        }
    }
}
