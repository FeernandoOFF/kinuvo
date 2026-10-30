package kinuvo.ui.session

import kinuvo.app.agent.*
import kinuvo.app.session.*
import kinuvo.protocol.ExecutionPrompt
import kinuvo.ui.agent.*

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.*
import com.composables.ui.components.*
import com.composables.ui.theme.*
import kinuvo.ui.design.*

/**
 * Above an external Agent's composer: why it can't reply yet, with what fixes it, then what its harness waits on,
 * answered from any Device with access.
 */
@Composable
internal fun ExternalPrompts(state: ChatState, actions: ChatActions, modifier: Modifier = Modifier) {
    val agent = state.agent ?: return
    val harness = state.harness ?: "The harness"
    val host = agent.host ?: "its Server"
    val uri = LocalUriHandler.current
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        state.capabilities?.chat?.takeIf { state.ended == null }?.takeUnless { it.actionable }?.let { chat ->
            val r = readiness(chat, harness, host, agent.name)
            if (ReadinessAction.CheckAgain in r.actions) Button(onClick = actions.onCheckHarness, style = ButtonStyle.Outlined, buttonSize = ButtonSize.Small) { Text("Check again") }
        }
        state.prompts.forEach { prompt ->
            key(prompt.id) {
                when (prompt) {
                    is ExecutionPrompt.Approval -> ExternalApprovalCard(prompt, harness, actions)
                    is ExecutionPrompt.Questions -> QuestionsCard(prompt.asked, state.answers, actions.onEditAnswer, { actions.onExternal(prompt, questionsAnswer(prompt, it)) }, skip = if (prompt.native) "Skip" else "Decline", attached = true)
                    is ExecutionPrompt.OpenLink -> RequestCard(Lucide.ExternalLink, "Open ${prompt.site}", prompt.message) {
                        Button({ uri.openUri(prompt.url); actions.onExternal(prompt, openLinkAnswer(true)) }) { Text("Open page") }
                        Button({ actions.onExternal(prompt, openLinkAnswer(false)) }, style = ButtonStyle.Ghost) { Text("Decline") }
                    }
                    is ExecutionPrompt.Unsupported -> RequestCard(Lucide.MessageCircleQuestionMark, prompt.message, "Kinuvo can't show this request. Answer it in $harness on $host, or decline it.") {
                        Button(onClick = { actions.onExternal(prompt, questionsAnswer(prompt, null)) }, style = ButtonStyle.Ghost, contentColor = color(mutedColor)) { Text("Decline") }
                    }
                }
            }
        }
    }
}

/** A harness's permission request: what it's about, then one button per option it offered, with its own labels. */
@Composable
private fun ExternalApprovalCard(prompt: ExecutionPrompt.Approval, harness: String, actions: ChatActions) {
    RequestCard(Lucide.ShieldAlert, "$harness wants to: ${prompt.title}", prompt.description, prompt.origin, if (prompt.site != null) Lucide.ExternalLink else Lucide.Plug) {
        prompt.options.forEach { option ->
            val answer = { actions.onExternal(prompt, approvalAnswer(option.id)) }
            when (approvalOptionStyle(option.kind)) {
                ApprovalOptionStyle.Primary -> Button(onClick = answer, style = ButtonStyle.Primary) { Text(option.label) }
                ApprovalOptionStyle.Outlined -> Button(onClick = answer, style = ButtonStyle.Outlined) { Text(option.label) }
                ApprovalOptionStyle.Destructive -> Button(onClick = answer, style = ButtonStyle.Ghost, contentColor = color(destructiveColor)) { Text(option.label) }
            }
        }
    }
}

@Composable
private fun RequestCard(icon: ImageVector, title: String, detail: String?, origin: String? = null, originIcon: ImageVector = Lucide.Plug, buttons: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(icon, Modifier.size(18.dp), tint = color(accentColor))
                Text(title, Modifier.weight(1f), fontWeight = FontWeight.Normal)
            }
            detail?.let { Text(it, fontSize = 13.sp, color = color(mutedColor)) }
            origin?.let {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(originIcon, Modifier.size(14.dp), tint = color(mutedColor))
                    Text(it, fontSize = 12.sp, color = color(mutedColor), maxLines = 1)
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { buttons() }
        }
    }
}
