package kinuvo.ui.session

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Hand
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.MessageCircleQuestionMark
import com.composables.icons.lucide.ShieldAlert
import com.composables.icons.lucide.X
import com.composables.ui.components.*
import com.composables.ui.theme.*
import kinuvo.app.session.*
import kinuvo.protocol.AgentEvent
import kinuvo.protocol.ApprovalDecision
import kinuvo.protocol.Question
import kinuvo.protocol.Risk
import kinuvo.ui.design.*

private val ComposerShape = RoundedCornerShape(28.dp)
private const val ArgumentLines = 8
private val TrayShape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)
private val WideBreakpoint = 600.dp
private val BodyMaxHeight = 380.dp
private val FocusRing = 4.dp

/** Widens the clip bounds by [inset] on each side without changing layout, so a focus ring drawn outside a field isn't cut off. */
private fun Modifier.bleed(inset: Dp) = layout { measurable, constraints ->
    val px = inset.roundToPx()
    val placeable = measurable.measure(constraints.offset(2 * px, 2 * px))
    layout(placeable.width - 2 * px, placeable.height - 2 * px) { placeable.place(-px, -px) }
}

/** In-progress input for one request; kept across a resize between morph and tray. */
internal class RequestInput(note: String = "") {
    val note = TextFieldState(note)
    var step by mutableIntStateOf(0)
    var arguments by mutableStateOf(false)
}

/** The [RequestInput] of each open request, by request ID. */
internal class RequestInputs {
    private val inputs = HashMap<String, RequestInput>()

    fun of(request: PendingRequest) = inputs.getOrPut(request.id) {
        RequestInput((request as? PendingRequest.Takeover)?.asked?.questions?.firstOrNull()?.default?.firstOrNull().orEmpty())
    }

    fun retain(ids: Set<String>) = inputs.keys.retainAll(ids)
}

/**
 * Shows what the Agent waits on in place of the chat input: the first of `state.requests` as the [composer] itself on narrow widths, or a tray over it on wide ones.
 * [tray] forces the tray at any width, for a [composer] that can't be replaced. [notice] is shown above the morphed card, or above a forced tray.
 * With no native request, it is just [composer].
 */
@Composable
internal fun RequestComposer(
    state: ChatState,
    actions: ChatActions,
    inputs: RequestInputs,
    modifier: Modifier = Modifier,
    tray: Boolean = false,
    notice: (@Composable () -> Unit)? = null,
    composer: @Composable () -> Unit,
) {
    val requests = state.requests
    val request = requests.firstOrNull()?.takeUnless { it is PendingRequest.External }
    LaunchedEffect(requests) { inputs.retain(requests.map { it.id }.toSet()) }
    val body: @Composable (PendingRequest, Boolean) -> Unit = { current, wide ->
        RequestHeader(current, requests.size, onClose = if (current is PendingRequest.Questions) ({ actions.onAnswer(null) }) else null)
        RequestBody(current, state.answers, inputs.of(current), actions, wide)
    }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val wide = maxWidth >= WideBreakpoint
        if (tray || wide) Tray(request, notice.takeIf { tray }, composer) { body(it, wide) }
        else Morph(request, notice, composer) { body(it, false) }
    }
}

@Composable
private fun Morph(request: PendingRequest?, notice: (@Composable () -> Unit)?, composer: @Composable () -> Unit, body: @Composable (PendingRequest) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (request != null) notice?.invoke()
        MorphCard(request, composer, body)
    }
}

@Composable
private fun MorphCard(request: PendingRequest?, composer: @Composable () -> Unit, body: @Composable (PendingRequest) -> Unit) {
    AnimatedContent(request, Modifier.fillMaxWidth(), contentKey = { it?.id }, contentAlignment = Alignment.BottomCenter, transitionSpec = {
        (fadeIn(tween(200, delayMillis = 90)) + scaleIn(spring(0.8f, Spring.StiffnessMediumLow), initialScale = 0.96f))
            .togetherWith(fadeOut(tween(90)))
            .using(SizeTransform(clip = false) { _, _ -> spring(0.85f, Spring.StiffnessMediumLow) })
    }) { current ->
        if (current == null) composer()
        else Column(
            Modifier.fillMaxWidth().clip(ComposerShape).background(color(fieldColor), ComposerShape)
                .border(1.dp, current.tone().copy(alpha = 0.55f), ComposerShape).padding(horizontal = 18.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            body(current)
        }
    }
}

@Composable
private fun Tray(request: PendingRequest?, notice: (@Composable () -> Unit)?, composer: @Composable () -> Unit, body: @Composable (PendingRequest) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        if (request != null) notice?.let { Box(Modifier.padding(bottom = 6.dp)) { it() } }
        AnimatedVisibility(
            request != null,
            enter = expandVertically(spring(0.85f, Spring.StiffnessMediumLow), expandFrom = Alignment.Bottom) + fadeIn(),
            exit = shrinkVertically(shrinkTowards = Alignment.Bottom) + fadeOut(),
        ) {
            var shown by remember { mutableStateOf(request) }
            request?.let { shown = it }
            shown?.let { current ->
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp).clip(TrayShape).background(color(panelColor)).border(1.dp, color(borderColor), TrayShape)
                        .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    body(current)
                }
            }
        }
        // Lifts over the tray's bottom edge so the two read as one object.
        val lift by animateDpAsState(if (request != null) 14.dp else 0.dp)
        Box(Modifier.layout { measurable, constraints ->
            val placeable = measurable.measure(constraints)
            val up = lift.roundToPx()
            layout(placeable.width, placeable.height - up) { placeable.place(0, -up) }
        }) { composer() }
    }
}

@Composable
private fun PendingRequest.tone(): Color = when (this) {
    is PendingRequest.Approval -> when (event.risk) {
        Risk.High -> color(destructiveColor)
        Risk.Medium -> color(warningColor)
        Risk.Low -> color(accentColor)
    }
    else -> color(accentColor)
}

private fun PendingRequest.icon(): ImageVector = when (this) {
    is PendingRequest.Approval -> Lucide.ShieldAlert
    is PendingRequest.Takeover -> Lucide.Hand
    else -> Lucide.MessageCircleQuestionMark
}

private fun PendingRequest.title(): String = when (this) {
    is PendingRequest.Approval -> if (event.isShell()) "Run this command?" else "Allow ${event.identity?.localId ?: event.toolCallName}?"
    is PendingRequest.Questions -> asked.title ?: "Questions for you"
    is PendingRequest.Takeover -> "Your turn"
    is PendingRequest.External -> ""
}

private fun PendingRequest.detail(): String? = when (this) {
    is PendingRequest.Approval -> event.reason
    is PendingRequest.Takeover -> reason ?: asked.title
    else -> null
}

private fun AgentEvent.ApprovalRequested.isShell() = identity?.instance?.pluginId == "kinuvo.exec" && identity?.localId == "shell"

@Composable
private fun RequestHeader(request: PendingRequest, count: Int, onClose: (() -> Unit)?) {
    val tone = request.tone()
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(30.dp).background(tone.copy(alpha = 0.14f), CircleShape), contentAlignment = Alignment.Center) { Icon(request.icon(), Modifier.size(16.dp), tint = tone) }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(request.title(), Modifier.weight(1f), fontWeight = FontWeight.Normal, singleLine = true, overflow = TextOverflow.Ellipsis)
                if (request is PendingRequest.Approval) Pill("${request.event.risk.name} risk", tone = when (request.event.risk) { Risk.Low -> Tone.Neutral; Risk.Medium -> Tone.Warning; Risk.High -> Tone.Danger })
                if (count > 1) Text("1 of $count", fontSize = 12.sp, color = color(mutedColor), singleLine = true)
            }
            request.detail()?.let { Text(it, fontSize = 12.sp, color = color(mutedColor), maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
        if (onClose != null) IconButton(onClick = onClose, style = ButtonStyle.Ghost, shape = CircleShape, contentColor = color(mutedColor), modifier = Modifier.size(32.dp)) {
            Icon(Lucide.X, Modifier.size(18.dp), contentDescription = "Dismiss")
        }
    }
}

@Composable
private fun RequestBody(request: PendingRequest, drafts: Map<String, Map<String, List<String>>>, input: RequestInput, actions: ChatActions, wide: Boolean) {
    when (request) {
        is PendingRequest.Approval -> ApprovalBody(request.event, input, wide) { actions.onDecision(request.id, it) }
        is PendingRequest.Questions -> QuestionsBody(request.asked, drafts, input, wide, actions)
        is PendingRequest.Takeover -> TakeoverBody(request.asked, input, wide, actions)
        is PendingRequest.External -> Unit
    }
}

/** Primary action first and every button full width when stacked; one row aligned right with the primary last when [wide]. */
@Composable
private fun Actions(wide: Boolean, primary: @Composable (Modifier) -> Unit, others: @Composable (Modifier) -> Unit) {
    if (wide) FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        others(Modifier)
        primary(Modifier)
    } else Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        primary(Modifier.fillMaxWidth())
        others(Modifier.fillMaxWidth())
    }
}

@Composable
private fun ApprovalBody(event: AgentEvent.ApprovalRequested, input: RequestInput, wide: Boolean, onDecision: (ApprovalDecision) -> Unit) {
    val identity = event.identity
    // The title already names a tool without an identity.
    val label = identity?.let { "${it.instance.pluginId} › ${it.instance.instanceId} › ${it.localId}" }
    val always = event.trustHint?.let { if (identity?.instance?.pluginId == "kinuvo.computer" && identity.localId == "computer_act") "Always allow in $it" else "Always allow `$it …`" } ?: "Always allow"
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val command = if (event.isShell()) commandOf(event.args) else null
        if (command != null) {
            Text("$ $command", Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(color(codeColor)).padding(horizontal = 12.dp, vertical = 9.dp),
                style = MonoStyle, color = color(onCodeColor), maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        val json = prettyJson(event.args)
        val toggle = when {
            command != null -> if (input.arguments) "Hide arguments" else "Arguments"
            json.lines().size > ArgumentLines -> if (input.arguments) "Show less" else "Show all"
            else -> null
        }
        if (label != null || toggle != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label.orEmpty(), Modifier.weight(1f), fontSize = 12.sp, color = color(mutedColor), singleLine = true, overflow = TextOverflow.Ellipsis)
            toggle?.let { Button(onClick = { input.arguments = !input.arguments }, modifier = Modifier.heightIn(min = 44.dp), style = ButtonStyle.Link, contentColor = color(mutedColor)) {
                Text(it, fontSize = 12.sp, singleLine = true)
            } }
        }
        // Without a command to show, the arguments are what's being approved.
        if (command == null || input.arguments) CodeBlock(json, language = "Arguments", maxLines = if (input.arguments) Int.MAX_VALUE else ArgumentLines)
        Actions(wide,
            primary = { Button(onClick = { onDecision(ApprovalDecision.AllowOnce) }, modifier = it, style = ButtonStyle.Primary) { Text("Allow once") } },
            others = {
                if (event.trustable) {
                    Button(onClick = { onDecision(ApprovalDecision.AllowForSession) }, modifier = it, style = ButtonStyle.Outlined) { Text("Allow for this Session", singleLine = true) }
                    Button(onClick = { onDecision(ApprovalDecision.AlwaysAllow) }, modifier = it, style = ButtonStyle.Outlined) { Text(always, singleLine = true, overflow = TextOverflow.Ellipsis) }
                }
            })
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Note for the Agent if you deny (optional)", fontSize = 12.sp, color = color(mutedColor))
            Input(input.note, "What to do instead", capitalize = true)
        }
        Button(onClick = { onDecision(ApprovalDecision.Deny(input.note.text.toString())) }, modifier = Modifier.fillMaxWidth(), style = ButtonStyle.Outlined,
            contentColor = color(destructiveColor)) { Text("Deny") }
    }
}

@Composable
private fun QuestionsBody(asked: AgentEvent.QuestionsAsked, drafts: Map<String, Map<String, List<String>>>, input: RequestInput, wide: Boolean, actions: ChatActions) {
    val answers = asked.questions.associate { it.id to it.default } + drafts[asked.questionsId].orEmpty()
    fun ready(q: Question) = !q.required || answers[q.id].orEmpty().any(String::isNotBlank)
    val stepped = !wide && asked.questions.size > 1
    val step = input.step.coerceIn(0, maxOf(asked.questions.lastIndex, 0))
    val last = !stepped || step == asked.questions.lastIndex
    val shown = if (stepped) listOf(asked.questions[step]) else asked.questions
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (stepped) Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                asked.questions.indices.forEach { i -> Box(Modifier.height(4.dp).weight(1f).clip(CircleShape).background(if (i <= step) color(accentColor) else color(borderColor))) }
            }
            Text("Question ${step + 1} of ${asked.questions.size}", fontSize = 12.sp, color = color(mutedColor))
        }
        Column(Modifier.bleed(FocusRing).heightIn(max = BodyMaxHeight).verticalScroll(rememberScrollState()).padding(FocusRing), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            shown.forEach { q -> key(asked.questionsId, q.id) { QuestionField(q, answers[q.id].orEmpty()) { actions.onEditAnswer(asked.questionsId, q.id, it) } } }
        }
        Actions(wide,
            primary = {
                Button(onClick = { if (last) actions.onAnswer(answers) else input.step = step + 1 }, modifier = it, enabled = if (last) asked.questions.all(::ready) else ready(asked.questions[step]), style = ButtonStyle.Primary) {
                    Text(if (asked.questions.isEmpty()) "Check again" else if (last) "Send" else "Next")
                }
            },
            others = {
                if (stepped && step > 0) Button(onClick = { input.step = step - 1 }, modifier = it, style = ButtonStyle.Ghost, contentColor = color(mutedColor)) { Text("Back") }
                val current = asked.questions.getOrNull(step)
                if (stepped && current != null && !current.required) {
                    val skipped = answers + (current.id to emptyList())
                    Button(onClick = {
                        actions.onEditAnswer(asked.questionsId, current.id, emptyList())
                        if (last) actions.onAnswer(skipped) else input.step = step + 1
                    }, modifier = it, enabled = !last || asked.questions.all { q -> q == current || ready(q) }, style = ButtonStyle.Ghost, contentColor = color(mutedColor)) { Text("Skip question") }
                }
            })
    }
}

@Composable
private fun TakeoverBody(asked: AgentEvent.QuestionsAsked, input: RequestInput, wide: Boolean, actions: ChatActions) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Hand back in Live view when finished. Answering or skipping keeps Takeover paused.", fontSize = 12.sp, color = color(mutedColor))
        Actions(wide,
            primary = { Button(onClick = { asked.screen?.let { actions.onScreen(it, asked.title) } ?: actions.onWatch() }, modifier = it, style = ButtonStyle.Primary) { Text("Open Live view") } },
            others = { Button(onClick = { actions.onAnswer(null) }, modifier = it, style = ButtonStyle.Ghost, contentColor = color(mutedColor)) { Text("Skip") } })
        val question = asked.questions.firstOrNull()
        val text = input.note.text.toString()
        Input(input.note, "Add a note for the agent", capitalize = true, trailing = {
            Button(onClick = { actions.onAnswer(mapOf((question?.id ?: "note") to listOfNotNull(text.takeIf(String::isNotBlank)))) }, enabled = question?.required != true || text.isNotBlank(),
                style = ButtonStyle.Ghost, contentColor = color(accentColor)) { Text("Send") }
        })
    }
}
