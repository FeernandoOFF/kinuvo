package kinuvo.server.tools

import kinuvo.server.plugin.toolSpec
import kinuvo.server.plugin.ParameterType
import kinuvo.server.plugin.ToolParameter
import kinuvo.protocol.ToolNames
import kinuvo.server.plugin.CoreTool
import kinuvo.protocol.ProtocolJson
import kinuvo.protocol.Question
import kinuvo.protocol.QuestionKind
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Shows the user a form and waits for the answers; the runner does the asking, since only it can reach the user. */
class AskUserTool(routine: Boolean = false) : CoreTool {
    override val descriptor = toolSpec(
        name = ToolNames.ASK_USER,
        description = "Ask the user one or more questions as a form and wait for their answers. Use it when you need " +
            "choices, details or confirmation you can't infer; group related questions in one call. Returns the answers " +
            "as JSON by question id, or says the user skipped." +
            (if (routine) " This is a Routine run: the user is notified and the run waits until they answer, so ask only when you can't go on without them." else ""),
        requiredParameters = listOf(
            ToolParameter(
                "questions", "The form's fields, in order",
                ParameterType.List(ParameterType.Object(
                    properties = listOf(
                        ToolParameter("id", "Unique key for the answer", ParameterType.String),
                        ToolParameter("label", "The question", ParameterType.String),
                        ToolParameter("kind", "single/multi pick from options; yes_no; text, long_text, number, date (YYYY-MM-DD)", ParameterType.Enum(KINDS)),
                        ToolParameter("options", "Choices for single and multi", ParameterType.List(ParameterType.String)),
                        ToolParameter("required", "Must be answered (default false)", ParameterType.Boolean),
                        ToolParameter("placeholder", "Hint shown in empty text fields", ParameterType.String),
                        ToolParameter("default", "Prefilled answer; for multi, any of the options", ParameterType.List(ParameterType.String)),
                    ),
                    requiredProperties = listOf("id", "label", "kind"),
                )),
            ),
        ),
        optionalParameters = listOf(ToolParameter("title", "Heading for the form", ParameterType.String)),
    )

    override suspend fun execute(args: JsonObject): String = error("ask_user runs through the runner")

    companion object {
        fun questions(args: JsonObject): List<Question> {
            val questions = ProtocolJson.decodeFromJsonElement(ListSerializer(Question.serializer()), args["questions"] ?: error("Missing 'questions'"))
            require(questions.isNotEmpty()) { "Give at least one question" }
            require(questions.size <= MAX_QUESTIONS) { "At most $MAX_QUESTIONS questions" }
            require(questions.map { it.id }.toSet().size == questions.size) { "Question ids must be unique" }
            questions.forEach { q ->
                require(q.id.isNotBlank() && q.label.isNotBlank()) { "Every question needs an id and a label" }
                if (q.kind.choice) require(q.options.isNotEmpty() && q.default.all { it in q.options }) { "'${q.id}' needs options, including its default" }
            }
            return questions
        }

        private const val MAX_QUESTIONS = 30
        private val KINDS = arrayOf("single", "multi", "yes_no", "text", "long_text", "number", "date")

        /** What the model sees: multi answers as arrays, the rest as strings; unanswered ones are left out. */
        fun result(questions: List<Question>, answers: Map<String, List<String>>?): String {
            if (answers == null) return "The user skipped the questions."
            val json = JsonObject(questions.mapNotNull { q ->
                val a = answers[q.id]?.filter { it.isNotBlank() }.orEmpty().ifEmpty { return@mapNotNull null }
                q.id to if (q.kind == QuestionKind.Multi) JsonArray(a.map(::JsonPrimitive)) else JsonPrimitive(a.first())
            }.toMap())
            return json.toString()
        }
    }
}
