package kinuvo.ui.session

import kinuvo.app.connection.*
import kinuvo.ui.agent.*
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
import kinuvo.protocol.AgentEvent
import kinuvo.protocol.Question
import kinuvo.protocol.QuestionKind
import kinuvo.ui.design.*

/** An `ask_user` form waiting in the chat, filled in from [drafts]; [ChatActions.onAnswer] gets the answers by question ID, or null to skip. */
@Composable
internal fun QuestionsCard(asked: AgentEvent.QuestionsAsked, drafts: Map<String, Map<String, List<String>>>, actions: ChatActions) =
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        asked.screen?.let { screen ->
            Button(onClick = { actions.onScreen(screen, asked.title) }, style = ButtonStyle.Outlined) { Text("Open requested Live view") }
            Text("Hand back in Live view when finished. Answering or skipping keeps Takeover paused.", color = color(mutedColor))
        }
        QuestionsCard(asked, drafts, actions.onEditAnswer, actions.onAnswer)
    }

/** [skip] names the button that answers null. */
@Composable
internal fun QuestionsCard(asked: AgentEvent.QuestionsAsked, drafts: Map<String, Map<String, List<String>>>,
    onEdit: (questionsId: String, questionId: String, value: List<String>) -> Unit, onAnswer: (Map<String, List<String>>?) -> Unit, skip: String = "Skip", attached: Boolean = false) {
    val answers = asked.questions.associate { it.id to it.default } + drafts[asked.questionsId].orEmpty()
    val ready = asked.questions.none { it.required && answers[it.id].orEmpty().none(String::isNotBlank) }
    Panel(
        Modifier.fillMaxWidth(),
        color = color(panelColor),
        borderColor = color(accentColor).copy(alpha = if (attached) 0f else 0.5f),
        shape = Theme[shapes][largeShape],
        contentPadding = PaddingValues(if (attached) 0.dp else 14.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Lucide.MessageCircleQuestionMark, Modifier.size(18.dp), tint = color(accentColor))
                Text(asked.title ?: "Questions for you", fontWeight = FontWeight.Normal)
            }
            asked.questions.forEach { q -> key(q.id) { QuestionField(q, answers[q.id].orEmpty()) { onEdit(asked.questionsId, q.id, it) } } }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onAnswer(answers) }, enabled = ready, style = ButtonStyle.Primary) { Text(if (asked.questions.isEmpty()) "Check again" else "Submit") }
                Button(onClick = { onAnswer(null) }, style = ButtonStyle.Ghost, contentColor = color(mutedColor)) { Text(skip) }
            }
        }
    }
}

@Composable
private fun QuestionField(q: Question, value: List<String>, onChange: (List<String>) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(if (q.required) "${q.label} *" else q.label, fontSize = 13.sp, fontWeight = FontWeight.Normal)
        when (q.kind) {
            QuestionKind.Single, QuestionKind.YesNo -> RadioGroup(value.firstOrNull(), { onChange(listOf(it)) }) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    (if (q.kind == QuestionKind.YesNo) listOf("Yes", "No") else q.options).forEach { Radio(it) { Text(it) } }
                }
            }
            QuestionKind.Multi -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
