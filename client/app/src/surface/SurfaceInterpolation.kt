package kinuvo.app.surface

import kotlinx.serialization.json.*

internal fun SurfaceFunctions.interpolate(template: String): String {
    require(template.length <= 100_000)
    val result = StringBuilder()
    var cursor = 0
    while (cursor < template.length) {
        val start = template.indexOf("\${", cursor)
        if (start < 0) { result.append(template.substring(cursor)); break }
        result.append(template.substring(cursor, start))
        var end = start + 2
        var depth = 0
        var quote: Char? = null
        var escaped = false
        while (end < template.length) {
            val c = template[end]
            if (quote != null) {
                if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == quote) quote = null
            } else when (c) {
                '"', '\'' -> quote = c
                '{' -> depth++
                '}' -> if (depth == 0) break else depth--
            }
            end++
        }
        require(end < template.length) { "Unclosed interpolation" }
        val value = expression(template.substring(start + 2, end), 0)
        result.append(if (value is JsonPrimitive) value.content else value.toString())
        require(result.length <= 100_000)
        cursor = end + 1
    }
    return result.toString()
}

private fun SurfaceFunctions.expression(source: String, depth: Int): JsonElement {
    require(depth < 32)
    val value = source.trim()
    if (value.startsWith("\${") && value.endsWith('}')) return expression(value.substring(2, value.lastIndex), depth + 1)
    if (value.startsWith('\'') && value.endsWith('\'')) return JsonPrimitive(value.substring(1, value.lastIndex).replace("\\'", "'").replace("\\\\", "\\"))
    if (value.startsWith('"') || value.startsWith('{') || value.startsWith('[') || value in listOf("true", "false", "null") || value.toDoubleOrNull() != null)
        return Json.parseToJsonElement(value)
    val open = value.indexOf('(')
    if (open < 0) return evaluate(buildJsonObject { put("path", value) })
    require(value.endsWith(')'))
    val name = value.substring(0, open).trim()
    val parts = splitExpression(value.substring(open + 1, value.lastIndex), ',')
    val names = when (name) {
        "count" -> listOf("values", "where")
        "sum" -> listOf("values", "fields")
        "multiply" -> listOf("values")
        "formatDuration" -> listOf("minutes")
        "formatCurrency" -> listOf("value", "currency")
        else -> listOf("value")
    }
    val args = parts.filter { it.isNotBlank() }.mapIndexed { index, part ->
        val named = splitExpression(part, ':')
        if (named.size == 2) named[0].trim() to expression(named[1], depth + 1)
        else names[index] to expression(part, depth + 1)
    }.toMap()
    return evaluate(buildJsonObject { put("call", name); put("args", JsonObject(args)) })
}

private fun splitExpression(source: String, delimiter: Char): List<String> {
    var depth = 0
    var quote: Char? = null
    var escaped = false
    var start = 0
    val parts = mutableListOf<String>()
    source.forEachIndexed { index, c ->
        if (quote != null) {
            if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == quote) quote = null
        } else when (c) {
            '"', '\'' -> quote = c
            '(', '{', '[' -> depth++
            ')', '}', ']' -> depth--
            delimiter -> if (depth == 0) { parts += source.substring(start, index); start = index + 1 }
        }
    }
    parts += source.substring(start)
    return parts
}
