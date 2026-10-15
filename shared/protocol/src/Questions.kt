package kinuvo.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One field of an `ask_user` form. Choice kinds pick from [options]; [default] prefills the answer. */
@Serializable
data class Question(
    val id: String,
    val label: String,
    val kind: QuestionKind = QuestionKind.Text,
    val options: List<String> = emptyList(),
    val required: Boolean = false,
    val placeholder: String? = null,
    val default: List<String> = emptyList(),
)

@Serializable
enum class QuestionKind {
    @SerialName("single") Single,
    @SerialName("multi") Multi,
    @SerialName("text") Text,
    @SerialName("long_text") LongText,
    @SerialName("number") Number,
    @SerialName("date") Date,
    @SerialName("yes_no") YesNo;

    val choice get() = this == Single || this == Multi
}

/** The user's answers by [Question.id]; null [answers] = skipped. */
@Serializable
data class QuestionsParams(val questionsId: String, val answers: Map<String, List<String>>? = null)
