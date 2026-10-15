package kinuvo.harness.core.tools

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import kinuvo.protocol.ProtocolJson
import kinuvo.protocol.Question
import kinuvo.protocol.QuestionKind
import kinuvo.protocol.Risk
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Shows the user a form and waits for the answers; the runner does the asking, since only it can reach the user. */
class AskUserTool(routine: Boolean = false) : Tool {
    override val kind = ToolKind.AskUser
    override val risk = Risk.Low
    override val descriptor = ToolDescriptor(
        name = ToolNames.ASK_USER,
        description = "Ask the user one or more questions as a form and wait for their answers. Use it when you need " +
            "choices, details or confirmation you can't infer; group related questions in one call. Returns the answers " +
            "as JSON by question id, or says the user skipped." +
            (if (routine) " This is a Routine run: the user is notified and the run waits until they answer, so ask only when you can't go on without them." else ""),
        requiredParameters = listOf(
            ToolParameterDescriptor(
                "questions", "The form's fields, in order",
                ToolParameterType.List(ToolParameterType.Object(
                    properties = listOf(
                        ToolParameterDescriptor("id", "Unique key for the answer", ToolParameterType.String),
                        ToolParameterDescriptor("label", "The question", ToolParameterType.String),
                        ToolParameterDescriptor("kind", "single/multi pick from options; yes_no; text, long_text, number, date (YYYY-MM-DD)", ToolParameterType.Enum(KINDS)),
                        ToolParameterDescriptor("options", "Choices for single and multi", ToolParameterType.List(ToolParameterType.String)),
                        ToolParameterDescriptor("required", "Must be answered (default false)", ToolParameterType.Boolean),
                        ToolParameterDescriptor("placeholder", "Hint shown in empty text fields", ToolParameterType.String),
                        ToolParameterDescriptor("default", "Prefilled answer; for multi, any of the options", ToolParameterType.List(ToolParameterType.String)),
                    ),
                    requiredProperties = listOf("id", "label", "kind"),
                )),
            ),
        ),
        optionalParameters = listOf(ToolParameterDescriptor("title", "Heading for the form", ToolParameterType.String)),
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
