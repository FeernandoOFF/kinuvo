package kinuvo.server

import kinuvo.protocol.approval.Question
import kinuvo.protocol.approval.QuestionKind
import kinuvo.server.tools.AskUserTool
import kotlinx.serialization.json.*

/** A typed Review form for objects and fixed arrays of flat objects, or one text field. */
internal class ReviewForm private constructor(val questions: List<Question>, private val fields: JsonObject?, private val array: JsonArray? = null, private val arrayKey: String? = null) {
    /** The answers as JSON with the same keys and types as the input, or the plain text of the single field. */
    fun output(answers: Map<String, List<String>>): String {
        fun answer(id: String) = answers[id]?.firstOrNull().orEmpty()
        val original = fields ?: return answer(TEXT)
        val rebuilt = JsonObject(original.mapValues { (key, value) ->
            val text = answer(key)
            when {
                value is JsonObject || value is JsonArray -> try { Json.parseToJsonElement(text) } catch (_: IllegalArgumentException) { error("Review field '$key' is not valid JSON") }
                value is JsonNull -> if (text.isBlank()) JsonNull else JsonPrimitive(text)
                (value as JsonPrimitive).isString -> JsonPrimitive(text)
                value.booleanOrNull != null -> JsonPrimitive(text == "Yes")
                text.isBlank() -> JsonNull
                else -> text.trim().let { n -> n.toLongOrNull()?.let(::JsonPrimitive) ?: n.toDoubleOrNull()?.let(::JsonPrimitive) } ?: error("Review field '$key' is not a number")
            }
        })
        if (array == null) return rebuilt.toString()
        val items = JsonArray(array.mapIndexed { i, item -> JsonObject((item as JsonObject).mapValues { (key, _) -> rebuilt.getValue("items.$i.$key") }) })
        return (arrayKey?.let { JsonObject(mapOf(it to items)) } ?: items).toString()
    }

    companion object {
        private const val TEXT = "value"
        private const val LONG = 80
        private val pretty = Json { prettyPrint = true }

        fun of(rendered: String): ReviewForm {
            val parsed = WorkflowReferences.json(rendered)
            val arrayKey = (parsed as? JsonObject)?.takeIf { it.size == 1 }?.entries?.first()?.takeIf { it.value is JsonArray }?.key
            val array = (parsed as? JsonArray ?: arrayKey?.let { (parsed as JsonObject)[it] as JsonArray })?.takeIf { values ->
                values.isNotEmpty() && values.all { it is JsonObject && it.isNotEmpty() && it.all { (key, value) -> key.isNotBlank() && value is JsonPrimitive } }
            }
            val fields = array?.let { JsonObject(it.flatMapIndexed { i, item -> (item as JsonObject).map { (key, value) -> "items.$i.$key" to value } }.toMap()) } ?: parsed as? JsonObject
            if (fields == null || fields.isEmpty() || fields.size > AskUserTool.MAX_QUESTIONS || fields.keys.any { it.isBlank() })
                return ReviewForm(listOf(Question(TEXT, "Value", QuestionKind.LongText, default = listOf(parsed?.let { pretty.encodeToString(JsonElement.serializer(), it) } ?: rendered))), null)
            return ReviewForm(fields.map { (key, value) ->
                val label = if (array != null) "Item ${key.substringAfter("items.").substringBefore('.').toInt() + 1} · ${key.substringAfter("items.").substringAfter('.')}" else key
                when {
                    value is JsonObject || value is JsonArray -> Question(key, label, QuestionKind.LongText, default = listOf(pretty.encodeToString(JsonElement.serializer(), value)))
                    value is JsonNull -> Question(key, label)
                    (value as JsonPrimitive).isString -> Question(key, label, if (value.content.length > LONG || '\n' in value.content) QuestionKind.LongText else QuestionKind.Text, default = listOf(value.content))
                    value.booleanOrNull != null -> Question(key, label, QuestionKind.YesNo, default = listOf(if (value.boolean) "Yes" else "No"))
                    else -> Question(key, label, QuestionKind.Number, default = listOf(value.content))
                }
            }, fields, array, arrayKey)
        }
    }
}
