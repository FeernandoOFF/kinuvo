package kinuvo.ui.session

import kinuvo.app.connection.*
import kinuvo.ui.settings.*
import kinuvo.ui.board.*

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.MessageCircleQuestionMark
import com.composables.ui.components.*
import com.composables.ui.theme.*
import com.composeunstyled.theme.Theme
import kinuvo.protocol.PendingInteraction
import kinuvo.protocol.Question
import kinuvo.protocol.QuestionKind
import kinuvo.ui.design.*

/** [skip] names the button that answers null. */
@Composable
internal fun QuestionsCard(asked: PendingInteraction.Questions, drafts: Map<String, Map<String, List<String>>>,
    onEdit: (questionsId: String, questionId: String, value: List<String>) -> Unit, onAnswer: (Map<String, List<String>>?) -> Unit, skip: String = "Dismiss", attached: Boolean = false) {
    val answers = asked.questions.associate { it.id to it.default } + drafts[asked.id].orEmpty()
    val ready = asked.questions.none { it.required && answers[it.id].orEmpty().none(String::isNotBlank) }
    Panel(
        Modifier.fillMaxWidth(),
        color = color(panelColor),
        borderColor = color(accentColor).copy(alpha = if (attached) 0f else 0.5f),
        shape = Theme[shapes][largeShape],
        contentPadding = PaddingValues(if (attached) Spacing.none else Spacing.space14),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.space14)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.space8)) {
                Icon(Lucide.MessageCircleQuestionMark, Modifier.size(18.dp), tint = color(accentColor))
                Text(asked.title ?: "Questions for you", fontWeight = FontWeight.Normal)
            }
            asked.questions.forEach { q -> key(q.id) { QuestionField(q, answers[q.id].orEmpty()) { onEdit(asked.id, q.id, it) } } }
            // Laid out like the Agent's own requests: the two answers side by side.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.space8)) {
                Button(onClick = { onAnswer(answers) }, modifier = Modifier.weight(1f), enabled = ready, style = ButtonStyle.Primary) { Text(if (asked.questions.isEmpty()) "Check again" else "Submit") }
                Button(onClick = { onAnswer(null) }, modifier = Modifier.weight(1f), style = ButtonStyle.Outlined) { Text(skip) }
            }
        }
    }
}

@Composable
internal fun QuestionField(q: Question, value: List<String>, onChange: (List<String>) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.space6)) {
        Text(if (q.required) "${q.label} *" else q.label, style = TextStyles.secondary, fontWeight = FontWeight.Normal)
        when (q.kind) {
            QuestionKind.Single, QuestionKind.YesNo -> RadioGroup(value.firstOrNull(), { onChange(listOf(it)) }) {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.space4)) {
                    (if (q.kind == QuestionKind.YesNo) listOf("Yes", "No") else q.options).forEach { Radio(it) { Text(it) } }
                }
            }
            QuestionKind.Multi -> Column(verticalArrangement = Arrangement.spacedBy(Spacing.space4)) {
                q.options.forEach { option ->
                    Checkbox(option in value, { on -> onChange(if (on) value + option else value - option) }) { Text(option) }
                }
            }
            else -> {
                val text = rememberTextFieldState(value.firstOrNull().orEmpty())
                LaunchedEffect(text) { snapshotFlow { text.text.toString() }.collect { onChange(listOf(it)) } }
                val placeholder = q.placeholder?.ifBlank { null } ?: if (q.kind == QuestionKind.Date) "YYYY-MM-DD" else ""
                if (q.kind == QuestionKind.LongText) TextArea(text, placeholder, lines = 4)
                else Input(text, placeholder, capitalize = true)
            }
        }
    }
}
