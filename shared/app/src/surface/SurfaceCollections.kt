package kinuvo.app.surface

import kotlinx.serialization.json.*
import kotlin.math.roundToLong

fun appendSurfaceValue(values: JsonArray, value: JsonElement): JsonArray = JsonArray(values + value)

fun removeSurfaceValue(values: JsonArray, index: Int): JsonArray =
    if (index in values.indices) JsonArray(values.filterIndexed { i, _ -> i != index }) else values

fun countSurfaceValues(values: JsonArray, where: JsonObject? = null): Int = values.count { value ->
    where == null || value is JsonObject && where.all { (key, expected) ->
        val actual = value[key]
        actual == expected || actual is JsonArray && expected in actual
    }
}

fun surfaceValues(model: JsonElement, path: List<String>): List<JsonElement> {
    if (path.isEmpty()) return listOf(model)
    val next = path.first()
    val children = when {
        next == "*" && model is JsonArray -> model.toList()
        model is JsonObject -> listOfNotNull(model[next])
        model is JsonArray -> listOfNotNull(next.toIntOrNull()?.let { model.getOrNull(it) })
        else -> emptyList()
    }
    return children.flatMap { surfaceValues(it, path.drop(1)) }
}

fun sumSurfaceValues(values: List<JsonElement>): Double = values.sumOf {
    when (it) {
        is JsonArray -> sumSurfaceValues(it)
        is JsonPrimitive -> it.doubleOrNull?.takeIf(Double::isFinite) ?: 0.0
        else -> 0.0
    }
}

fun formatSurfaceDuration(minutes: Double): String {
    val total = minutes.takeIf(Double::isFinite)?.coerceAtLeast(0.0)?.roundToLong() ?: 0L
    return "${total / 60}h ${(total % 60).toString().padStart(2, '0')}m"
}

fun stepperValue(value: Double, step: Double, min: Double = 0.0, max: Double = Double.MAX_VALUE): Double {
    require(min.isFinite() && max.isFinite() && max >= min)
    return ((value.takeIf(Double::isFinite) ?: min) + (step.takeIf(Double::isFinite) ?: 0.0)).coerceIn(min, max)
}

fun surfaceMarkdownTable(value: JsonElement): String {
    val rows = (value as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
    val columns = rows.flatMap { it.keys }.distinct()
    fun cell(value: JsonElement?): String = when (value) {
        is JsonArray -> value.joinToString(", ") { cell(it) }
        is JsonPrimitive -> value.contentOrNull.orEmpty()
        else -> ""
    }.replace("\\", "\\\\").replace("|", "\\|").replace("\n", "<br>").replace("\r", "")
    fun row(values: List<String>) = "| ${values.joinToString(" | ")} |"
    if (columns.isEmpty()) return ""
    return (listOf(row(columns.map { cell(JsonPrimitive(it)) }), row(columns.map { "---" })) +
        rows.map { item -> row(columns.map { cell(item[it]) }) }).joinToString("\n")
}
