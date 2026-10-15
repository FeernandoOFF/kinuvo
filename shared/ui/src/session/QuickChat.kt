package kinuvo.ui.session

import kinuvo.app.connection.*
import kinuvo.ui.agent.*
import kinuvo.ui.board.*
import kinuvo.ui.navigation.*

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.composables.icons.lucide.ArrowUp
import com.composables.icons.lucide.Lucide
import com.composables.ui.components.Icon
import com.composables.ui.components.Text
import com.composables.ui.theme.*
import kinuvo.protocol.AgentInfo
import kinuvo.ui.design.*
import kotlinx.coroutines.launch

private val PillShape = RoundedCornerShape(50)

internal val QuickChatBarHeight = 80.dp

/**
 * The shell's quick chat card on phones, like Discord's user panel: it floats over the foot of the rail and column, with
 * the [agent]'s avatar overlapping its left edge, its name, and the [draft] or a prompt. Tapping it opens [QuickChat].
 */
@Composable
internal fun QuickChatBar(agent: AgentInfo?, draft: TextFieldState, modifier: Modifier = Modifier, onOpen: () -> Unit) {
    AnimatedContent(
        agent,
        modifier.fillMaxWidth().height(QuickChatBarHeight),
        contentKey = { it?.id },
        transitionSpec = {
            ((slideInVertically(tween(240)) { -it / 3 } + fadeIn(tween(240))) togetherWith
                (slideOutVertically(tween(160)) { it / 3 } + fadeOut(tween(160))))
                .using(SizeTransform(clip = false))
        },
        label = "Quick chat agent",
    ) { shown -> QuickChatBarContent(shown, draft, onOpen) }
}

@Composable
private fun QuickChatBarContent(agent: AgentInfo?, draft: TextFieldState, onOpen: () -> Unit) {
    val avatar = 60.dp
    val card = RoundedCornerShape(20.dp)
    Box(Modifier.fillMaxWidth().height(QuickChatBarHeight).padding(start = (RailWidth - avatar) / 2, end = 12.dp, bottom = 10.dp), contentAlignment = Alignment.CenterStart) {
        Row(
            // Styled like the input it opens: QuickChat's accent-bordered field.
            Modifier.padding(start = avatar / 2).fillMaxWidth().height(avatar - 8.dp).shadow(12.dp, card).clip(card).background(color(fieldColor))
                .border(1.dp, color(accentColor).copy(alpha = 0.3f), card).clickable(enabled = agent != null, onClick = onOpen)
                .padding(start = avatar / 2 + 12.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(agent?.name ?: "…", fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Bold, color = color(mutedColor), singleLine = true, overflow = TextOverflow.Ellipsis)
                val text = draft.text.toString()
                Text(text.ifBlank { "Message ${agent?.name ?: "…"}" }, singleLine = true, overflow = TextOverflow.Ellipsis,
                    color = if (text.isBlank()) color(mutedColor) else color(onBackgroundColor))
            }
            Box(Modifier.size(36.dp).clip(CircleShape).background(color(accentColor)), contentAlignment = Alignment.Center) {
                Icon(Lucide.ArrowUp, Modifier.size(18.dp), tint = color(onAccentColor))
            }
        }
        Box(
            Modifier.size(avatar).shadow(8.dp, CircleShape).clip(CircleShape).background(color(selectedControlColor))
                .border(2.dp, color(railColor), CircleShape).clickable(enabled = agent != null, onClick = onOpen),
            contentAlignment = Alignment.Center,
        ) { AgentAvatar(agent, 42.dp) }
    }
}

/**
 * Quick chat over everything else: pick an Agent and type. Send starts a Session with it and opens it; a long press sends
 * it without leaving. Tapping outside or going back keeps the [draft] for later.
 */
@Composable
internal fun QuickChat(
    agents: List<AgentInfo>,
    agent: AgentInfo?,
    draft: TextFieldState,
    onSelectAgent: (String) -> Unit,
    onSend: suspend (agentId: String, text: String, open: Boolean) -> Boolean,
    onDismiss: () -> Unit,
) {
    var sending by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    NavigationBackHandler(rememberNavigationEventState(NavigationEventInfo.None), isBackEnabled = true) { onDismiss() }

    fun send(open: Boolean) {
        val message = draft.text.toString().trim()
        if (message.isEmpty() || sending || agent == null) return
        val target = agent.id
        val sendMessage = onSend
        sending = true
        scope.launch {
            if (sendMessage(target, message, open)) {
                draft.clearText()
                onDismiss()
            }
            sending = false
        }
    }

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(color(backgroundColor).copy(alpha = 0.6f))
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismiss))
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                agents.forEach { a ->
                    val selected = a.id == agent?.id
                    Row(
                        Modifier.clip(PillShape).background(if (selected) color(selectedControlColor) else color(panelColor))
                            .border(1.dp, if (selected) color(accentColor) else color(borderColor), PillShape)
                            .clickable { onSelectAgent(a.id) }.padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        AgentAvatar(a, 28.dp)
                        Text(a.name, fontSize = 14.sp, singleLine = true)
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().clip(PillShape).background(color(panelColor)).border(1.dp, color(accentColor), PillShape).padding(6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                AgentAvatar(agent, 36.dp, state = if (sending) kinuvo.app.agent.AvatarState.Working else kinuvo.app.agent.AvatarState.Idle)
                BasicTextField(
                    draft,
                    Modifier.weight(1f).focusRequester(focus),
                    enabled = !sending,
                    textStyle = inputTextStyle().copy(color = color(onBackgroundColor)),
                    cursorBrush = SolidColor(color(accentColor)),
                    lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 5),
                    keyboardOptions = inputKeyboard(capitalize = true).copy(imeAction = ImeAction.Send),
                    onKeyboardAction = { send(open = true) },
                    decorator = { field ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (draft.text.isEmpty()) Text("Message ${agent?.name ?: "…"}", color = color(mutedColor), style = inputTextStyle(), singleLine = true, overflow = TextOverflow.Ellipsis)
                            field()
                        }
                    },
                )
                if (sending) Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) { Spinner(size = 16.dp) }
                else if (draft.text.isNotBlank()) Box(
                    Modifier.size(36.dp).clip(CircleShape).background(color(accentColor))
                        .semantics { contentDescription = "Send. Long press to send without opening the chat" }
                        .pointerInput(agent) {
                            detectTapGestures(
                                onTap = { send(open = true) },
                                onLongPress = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); send(open = false) },
                            )
                        },
                    contentAlignment = Alignment.Center,
                ) { Icon(Lucide.ArrowUp, Modifier.size(18.dp), tint = color(onAccentColor)) }
            }
        }
    }
}
