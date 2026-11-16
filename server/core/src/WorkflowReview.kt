package kinuvo.server

import kinuvo.protocol.approval.Question
import kinuvo.protocol.approval.QuestionKind
import kinuvo.server.tools.AskUserTool
import kotlinx.serialization.json.*

/** The form a Review step shows for its value (one field per top-level key of a JSON object, else one text field), and the value rebuilt from the answers. */
internal class ReviewForm private constructor(val questions: List<Question>, private val fields: JsonObject?) {
    /** The answers as JSON with the same keys and types as the input, or the plain text of the single field. */
    fun output(answers: Map<String, List<String>>): String {
        fun answer(id: String) = answers[id]?.firstOrNull().orEmpty()
        val original = fields ?: return answer(TEXT)
        return JsonObject(original.mapValues { (key, value) ->
            val text = answer(key)
            when {
                value is JsonObject || value is JsonArray -> try { Json.parseToJsonElement(text) } catch (_: IllegalArgumentException) { error("Review field '$key' is not valid JSON") }
                value is JsonNull -> if (text.isBlank()) JsonNull else JsonPrimitive(text)
                (value as JsonPrimitive).isString -> JsonPrimitive(text)
                value.booleanOrNull != null -> JsonPrimitive(text == "Yes")
                text.isBlank() -> JsonNull
                else -> text.trim().let { n -> n.toLongOrNull()?.let(::JsonPrimitive) ?: n.toDoubleOrNull()?.let(::JsonPrimitive) } ?: error("Review field '$key' is not a number")
            }
        }).toString()
    }

    companion object {
        private const val TEXT = "value"
        private const val LONG = 80
        private val pretty = Json { prettyPrint = true }

        fun of(rendered: String): ReviewForm {
            val fields = try { Json.parseToJsonElement(rendered) as? JsonObject } catch (_: IllegalArgumentException) { null }
            if (fields == null || fields.isEmpty() || fields.size > AskUserTool.MAX_QUESTIONS || fields.keys.any { it.isBlank() })
                return ReviewForm(listOf(Question(TEXT, "Value", QuestionKind.LongText, default = listOf(rendered))), null)
            return ReviewForm(fields.map { (key, value) ->
                when {
                    value is JsonObject || value is JsonArray -> Question(key, key, QuestionKind.LongText, default = listOf(pretty.encodeToString(JsonElement.serializer(), value)))
                    value is JsonNull -> Question(key, key)
                    (value as JsonPrimitive).isString -> Question(key, key, if (value.content.length > LONG || '\n' in value.content) QuestionKind.LongText else QuestionKind.Text, default = listOf(value.content))
                    value.booleanOrNull != null -> Question(key, key, QuestionKind.YesNo, default = listOf(if (value.boolean) "Yes" else "No"))
                    else -> Question(key, key, QuestionKind.Number, default = listOf(value.content))
                }
            }, fields)
        }
    }
}
