package kinuvo.ui.voice

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.animateBounds
import androidx.compose.ui.layout.LookaheadScope
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.draw.scale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.*
import com.composables.ui.components.*
import com.composables.ui.theme.*
import kinuvo.app.connection.ConnectedServer
import kinuvo.app.connection.Preferences
import kinuvo.app.session.LiveCalls
import kinuvo.app.session.VoiceChatState
import kinuvo.app.session.VoicePhase
import kinuvo.ui.agent.ink
import kinuvo.ui.design.*
import kinuvo.protocol.session.AgentEvent
import kinuvo.protocol.agent.AgentInfo
import kinuvo.protocol.approval.ApprovalDecision
import kinuvo.protocol.approval.InteractionAnswer
import kinuvo.protocol.approval.PendingInteraction
import kinuvo.app.agent.ApprovalOptionStyle
import kinuvo.app.agent.approvalOptionStyle
import kinuvo.app.session.approvalAnswer
import kinuvo.protocol.approval.Risk
import kinuvo.ui.session.commandOf
import kinuvo.ui.session.prettyJson
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import org.koin.compose.koinInject
import kotlinx.coroutines.launch

/**
 * A live call, full screen: the Agent's orb, what it's doing, and the call's controls. [captions] shows the current
 * exchange in place of the space around the orb. Tapping the orb cuts a reply short, or else mutes; holding it talks,
 * muted or not, until let go. [time] pins the orb's animation, e.g. for screenshots.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun LiveCallScreen(
    agentName: String,
    ink: Color,
    voice: VoiceChatState,
    durationSeconds: Long,
    captions: Boolean,
    onMinimize: () -> Unit,
    onMute: (Boolean) -> Unit,
    onCaptions: (Boolean) -> Unit,
    onSpeaker: (Boolean) -> Unit,
    onEnd: () -> Unit,
    modifier: Modifier = Modifier,
    onInterrupt: () -> Unit = {},
    onHold: (Boolean) -> Unit = {},
    time: Float? = null,
) {
    val error = voice.error
    // A failed call keeps its place until closed, but stills and greys the orb so it doesn't read as live.
    val tint = if (error != null) lerp(ink, color(mutedColor), 0.75f) else ink
    Box(modifier.fillMaxSize().background(color(backgroundColor)).background(Brush.verticalGradient(listOf(tint.copy(alpha = 0.10f), Color.Transparent)))) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = Spacing.space20, vertical = Spacing.space12)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                RoundIcon(Lucide.ChevronDown, "Minimize call", onClick = onMinimize)
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(agentName, style = TextStyles.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (error != null) Text("Call ended", style = TextStyles.caption, color = color(mutedColor)) else LiveLabel(durationSeconds)
                }
                if (error == null) RoundIcon(if (voice.speaker) Lucide.Volume2 else Lucide.VolumeX, if (voice.speaker) "Mute replies" else "Play replies", onClick = { onSpeaker(!voice.speaker) })
                else Spacer(Modifier.size(44.dp))
            }
            // The orb glides between the middle and its place under the captions rather than jumping.
            LookaheadScope {
                val shown = captions && error == null
                Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        androidx.compose.animation.AnimatedVisibility(shown, enter = fadeIn(tween(Motion.enter)), exit = fadeOut(tween(Motion.fast))) {
                            Captions(voice, Modifier.fillMaxSize().padding(top = Spacing.space32))
                        }
                    }
                    val size = when { error != null -> 200.dp; shown -> 132.dp; else -> 280.dp }
                    val orb = Modifier.animateBounds(this@LookaheadScope, Modifier.size(size))
                    if (error != null) AgentOrb(tint, OrbMood.Idle, 0f, orb, time)
                    else OrbControl(voice, onMute, onInterrupt, onHold, orb) { AgentOrb(tint, mood(voice), level(voice), Modifier.fillMaxSize(), time) }
                    if (!shown) Spacer(Modifier.weight(1f))
                    if (error != null) Failure(error, Modifier.padding(vertical = Spacing.space20)) else Status(voice, Modifier.padding(vertical = Spacing.space16))
                }
            }
            if (error != null) Box(Modifier.fillMaxWidth().padding(bottom = Spacing.space8), contentAlignment = Alignment.Center) {
                Box(Modifier.widthIn(max = 320.dp).fillMaxWidth().height(56.dp).clip(PillShape).background(color(onBackgroundColor)).clickable(onClick = onEnd)
                    .semantics { role = Role.Button; contentDescription = "Close" }, contentAlignment = Alignment.Center) {
                    Text("Close", style = TextStyles.label, color = color(backgroundColor))
                }
            }
            else Row(Modifier.fillMaxWidth().padding(bottom = Spacing.space8), horizontalArrangement = Arrangement.spacedBy(Spacing.space32, Alignment.CenterHorizontally)) {
                CallButton(if (voice.paused) Lucide.MicOff else Lucide.Mic, if (voice.paused) "Unmute" else "Mute", selected = voice.paused) { onMute(!voice.paused) }
                CallButton(Lucide.Captions, "Captions", selected = captions) { onCaptions(!captions) }
                CallButton(Lucide.X, "End", tint = color(destructiveColor), onClick = onEnd)
            }
        }
    }
}

/** Why a call ended early, as calm reading text rather than an alarm. */
@Composable
private fun Failure(message: String, modifier: Modifier = Modifier) {
    Column(modifier.widthIn(max = 360.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.space8)) {
        Icon(Lucide.CircleAlert, Modifier.size(20.dp), tint = color(warningColor))
        Text(message, style = TextStyles.callout, textAlign = TextAlign.Center, maxLines = 4, overflow = TextOverflow.Ellipsis)
    }
}

/** A minimized live call in the app's strip: Mute, who and what it's doing, End. Tapping the row reopens the call. */
@Composable
fun RowScope.CallStrip(agentName: String, ink: Color, voice: VoiceChatState, durationSeconds: Long, needsYou: Boolean, onMute: (Boolean) -> Unit, onEnd: () -> Unit) {
    val error = voice.error
    if (error == null) RoundIcon(if (voice.paused) Lucide.MicOff else Lucide.Mic, if (voice.paused) "Unmute" else "Mute", size = 36, selected = voice.paused,
        background = if (voice.paused) null else color(onBackgroundColor).copy(alpha = 0.10f)) { onMute(!voice.paused) }
    else Spacer(Modifier.size(36.dp))
    Text(
        when {
            error != null -> "Call with $agentName ended"
            needsYou -> "$agentName needs your OK"
            else -> "$agentName · ${status(voice)} · ${clock(durationSeconds)}"
        },
        Modifier.weight(1f), style = TextStyles.secondary, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis,
        color = when { error != null -> color(mutedColor); needsYou -> color(warningColor); else -> lerp(ink, color(onBackgroundColor), 0.25f) },
    )
    RoundIcon(Lucide.X, if (error != null) "Close" else "End call", size = 36, background = if (error != null) color(onBackgroundColor).copy(alpha = 0.10f) else color(destructiveColor),
        tint = if (error != null) null else Color.White, onClick = onEnd)
}

@Composable
private fun OrbControl(voice: VoiceChatState, onMute: (Boolean) -> Unit, onInterrupt: () -> Unit, onHold: (Boolean) -> Unit, modifier: Modifier, orb: @Composable () -> Unit) {
    val haptics = LocalHapticFeedback.current
    val current by rememberUpdatedState(voice)
    val mute by rememberUpdatedState(onMute)
    val interrupt by rememberUpdatedState(onInterrupt)
    val hold by rememberUpdatedState(onHold)
    var pressed by remember { mutableStateOf(false) }
    val press by animateFloatAsState(when {
        voice.holding -> 1.06f
        pressed -> 0.95f
        else -> 1f
    }, Motion.voicePressSpring())
    fun tap() {
        haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
        if (current.phase == VoicePhase.Speaking) interrupt() else mute(!current.paused)
    }
    val label = when {
        voice.phase == VoicePhase.Speaking -> "Interrupt"
        voice.paused -> "Unmute"
        else -> "Mute"
    }
    Box(modifier.scale(press).semantics {
        role = Role.Button
        contentDescription = label
        onClick { tap(); true }
        customActions = listOf(CustomAccessibilityAction(if (voice.paused) "Unmute" else "Mute") { mute(!current.paused); true })
    }.pointerInput(Unit) {
        coroutineScope {
            detectTapGestures(onPress = {
                pressed = true
                val held = launch {
                    delay(250)
                    haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                    hold(true)
                }
                val up = tryAwaitRelease()
                pressed = false
                if (!held.isActive) { hold(false); haptics.performHapticFeedback(HapticFeedbackType.GestureEnd) }
                else { held.cancel(); if (up) tap() }
            })
        }
    }) { orb() }
}

@Composable
private fun Captions(voice: VoiceChatState, modifier: Modifier) {
    // Follows the latest words, so a long reply keeps showing what's being said rather than how it began.
    val scroll = rememberScrollState()
    LaunchedEffect(voice.caption, voice.heard) { scroll.animateScrollTo(scroll.maxValue) }
    val fade = Modifier.graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen).drawWithContent {
        drawContent()
        if (scroll.value > 0) drawRect(Brush.verticalGradient(0f to Color.Transparent, 64.dp.toPx() / size.height to Color.Black), blendMode = BlendMode.DstIn)
    }
    Column(modifier.then(fade).verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(Spacing.space12)) {
        if (voice.heard.isNotBlank()) Text(voice.heard, style = TextStyles.secondary.copy(fontStyle = FontStyle.Italic), color = color(mutedColor))
        Text(voice.caption, style = TextStyles.headline)
    }
}

@Composable
private fun Status(voice: VoiceChatState, modifier: Modifier = Modifier) {
    AnimatedContent(status(voice), modifier.animateContentSize(), transitionSpec = { fadeIn(tween(Motion.enter)) togetherWith fadeOut(tween(Motion.fast)) }) { text ->
        Text(text, style = TextStyles.secondary, color = color(mutedColor), textAlign = TextAlign.Center)
    }
}

@Composable
private fun LiveLabel(seconds: Long) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.space6)) {
        Box(Modifier.size(6.dp).background(color(successColor), CircleShape))
        Text("Live · ${clock(seconds)}", style = TextStyles.caption, color = color(mutedColor))
    }
}

@Composable
private fun CallButton(icon: ImageVector, label: String, selected: Boolean = false, tint: Color? = null, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.space6)) {
        RoundIcon(icon, label, size = 64, selected = selected, background = tint, tint = if (tint != null) Color.White else null, onClick = onClick)
        Text(label, style = TextStyles.caption, color = color(mutedColor))
    }
}

@Composable
private fun RoundIcon(icon: ImageVector, label: String, size: Int = 44, selected: Boolean = false, background: Color? = null, tint: Color? = null, onClick: () -> Unit) {
    val fill = background ?: if (selected) color(onBackgroundColor) else color(panelColor)
    val ink = tint ?: if (selected) color(backgroundColor) else color(onBackgroundColor)
    Box(
        Modifier.size(size.dp).clip(CircleShape).background(fill).clickable(onClick = onClick).semantics { role = Role.Button; contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, Modifier.size((size * 0.42f).dp), tint = ink) }
}

// Muting only stops the microphone, so the Agent still visibly works and speaks; only listening goes quiet.
private fun mood(voice: VoiceChatState) = when {
    voice.phase == VoicePhase.Thinking -> OrbMood.Thinking
    voice.phase == VoicePhase.Speaking -> OrbMood.Speaking
    voice.phase == VoicePhase.Listening && !voice.paused -> OrbMood.Listening
    else -> OrbMood.Idle
}

private fun level(voice: VoiceChatState) = if (voice.phase == VoicePhase.Speaking) voice.outputLevel else voice.inputLevel

private fun status(voice: VoiceChatState) = if (voice.holding) "Listening · let go when done" else when (voice.phase) {
    VoicePhase.Off, VoicePhase.Starting -> "Connecting…"
    VoicePhase.Listening -> if (voice.paused) "Muted" else "Listening"
    VoicePhase.Thinking -> if (voice.paused) "Working on it · muted" else "Working on it"
    VoicePhase.Speaking -> if (voice.paused) "Speaking · muted" else "Speaking"
}

private fun clock(seconds: Long) = "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"

/**
 * The active live call over whatever is on screen: full screen until minimized, then a floating bar above the app's
 * bottom controls. Captions stay as the user last left them.
 */
@Composable
fun LiveCallHost(agents: List<AgentInfo>) {
    val calls = koinInject<LiveCalls>()
    val prefs = koinInject<Preferences>()
    val sessions = koinInject<ConnectedServer>().sessions
    val scope = rememberCoroutineScope()
    val call = calls.state.collectAsState().value
    LaunchedEffect(agents) { agents.forEach { AgentInks.byId[it.id] = it.ink.toArgb().toLong() and 0xFFFFFFFFL } }
    val repo by sessions.state.collectAsState()
    var minimized by remember { mutableStateOf(false) }
    var captions by remember { mutableStateOf(prefs[CaptionsKey] == "on") }
    LaunchedEffect(call == null) { if (call == null) minimized = false }
    val shown = call ?: return
    val ink = agents.firstOrNull { it.id == shown.agentId }?.ink ?: color(accentColor)
    val live = repo.sessions[shown.sessionId]?.live
    val approval = live?.pending?.firstNotNullOfOrNull { it as? PendingInteraction.Approval }
    val external = live?.pending?.firstNotNullOfOrNull { it as? PendingInteraction.HarnessApproval }
    val haptics = LocalHapticFeedback.current
    val request = approval?.id ?: external?.id
    LaunchedEffect(request) {
        if (request == null) return@LaunchedEffect
        calls.attention()
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }
    val phase = shown.voice.phase
    var lastPhase by remember { mutableStateOf(phase) }
    LaunchedEffect(phase) {
        when {
            lastPhase == VoicePhase.Starting && phase == VoicePhase.Listening -> haptics.performHapticFeedback(HapticFeedbackType.Confirm)
            phase == VoicePhase.Thinking || phase == VoicePhase.Speaking -> if (lastPhase != phase) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
            else -> {}
        }
        lastPhase = phase
    }
    NavigationBackHandler(rememberNavigationEventState(NavigationEventInfo.None), isBackEnabled = !minimized) { minimized = true }
    AnimatedVisibility(!minimized, enter = slideInVertically { it / 3 } + fadeIn(), exit = slideOutVertically { it / 3 } + fadeOut()) {
        LiveCallScreen(shown.agentName, ink, shown.voice, shown.durationSeconds, captions,
            onMinimize = { minimized = true }, onMute = calls::mute,
            onCaptions = { captions = it; prefs[CaptionsKey] = if (it) "on" else null },
            onSpeaker = calls::speaker, onEnd = calls::end,
            onInterrupt = calls::interrupt, onHold = calls::hold,
            // An open request is modal: screen readers and keyboards stay on it, not the call behind.
            modifier = if (approval != null || external != null) Modifier.clearAndSetSemantics {} else Modifier)
    }
    AnimatedVisibility(!minimized && approval != null, enter = fadeIn(), exit = fadeOut()) {
        approval?.let { a ->
            CallApprovalSheet(shown.agentName, a, onDecision = { d -> scope.launch { runCatching { sessions.respond(shown.sessionId, a.id, InteractionAnswer.Approval(d)) } } }, onEnd = calls::end)
        }
    }
    AnimatedVisibility(!minimized && approval == null && external != null, enter = fadeIn(), exit = fadeOut()) {
        external?.let { p ->
            CallExternalApprovalSheet(shown.agentName, p, onOption = { o -> scope.launch { runCatching { sessions.respond(shown.sessionId, p.id, approvalAnswer(o)) } } }, onEnd = calls::end)
        }
    }
    if (minimized) Strip("live-call", if (shown.voice.error != null) color(mutedColor) else ink, onClick = { minimized = false }, priority = 100) {
        CallStrip(shown.agentName, ink, shown.voice, shown.durationSeconds, needsYou = approval != null || external != null, onMute = calls::mute, onEnd = calls::end)
    }
}

private const val CaptionsKey = "live.captions"

/**
 * An Approval the Agent needs while the call goes on: a sheet over the call naming what the tool will touch, with the
 * raw arguments and "Allow for this Session" behind Show details. Approving by voice is deliberately not offered.
 */
@Composable
fun CallApprovalSheet(agentName: String, event: PendingInteraction.Approval, onDecision: (ApprovalDecision) -> Unit, onEnd: () -> Unit, modifier: Modifier = Modifier) {
    val shell = event.identity?.instance?.pluginId == "kinuvo.exec" && event.identity?.localId == "shell"
    val command = if (shell) commandOf(event.args)?.let { "$ $it" } else null
    val tone = when (event.risk) { Risk.High -> color(destructiveColor); Risk.Medium -> color(warningColor); Risk.Low -> color(accentColor) }
    CallRequestSheet(
        event.id, if (shell) Lucide.SquareTerminal else Lucide.ShieldAlert, tone,
        if (shell) "$agentName wants to run a command" else "$agentName wants to use ${event.identity?.localId ?: event.toolCallName}",
        command ?: event.reason ?: event.trustHint, mono = command != null,
        buttons = listOfNotNull(
            if (event.trustable) SheetChoice("Always allow", filled = true) { onDecision(ApprovalDecision.AlwaysAllow) } else null,
            SheetChoice("Allow once", filled = true) { onDecision(ApprovalDecision.AllowOnce) },
            SheetChoice("Decline", filled = false) { onDecision(ApprovalDecision.Deny()) },
        ),
        onEnd, modifier,
    ) {
        Text(prettyJson(event.args), Modifier.fillMaxWidth().heightIn(max = 160.dp).clip(RoundedCornerShape(Radius.menu)).background(color(codeColor)).verticalScroll(rememberScrollState()).padding(Spacing.space12),
            style = MonoStyle, color = color(onCodeColor), overflow = TextOverflow.Ellipsis)
        Button(onClick = { onDecision(ApprovalDecision.AllowForSession) }, modifier = Modifier.fillMaxWidth(), style = ButtonStyle.Ghost, shape = PillShape) { Text("Allow for this Session") }
    }
}

/** An external Agent's harness asking permission during a call, with the harness's own options; declining ones last. */
@Composable
fun CallExternalApprovalSheet(agentName: String, prompt: PendingInteraction.HarnessApproval, onOption: (String) -> Unit, onEnd: () -> Unit, modifier: Modifier = Modifier) {
    val choices = prompt.options.sortedBy { approvalOptionStyle(it.kind) == ApprovalOptionStyle.Destructive }
        .map { o -> SheetChoice(o.label, filled = approvalOptionStyle(o.kind) != ApprovalOptionStyle.Destructive) { onOption(o.id) } }
    CallRequestSheet(prompt.id, if (prompt.site != null) Lucide.ExternalLink else Lucide.ShieldAlert, color(warningColor), "$agentName wants to: ${prompt.title}",
        prompt.origin, mono = false, buttons = choices, onEnd = onEnd, modifier = modifier,
        details = prompt.description?.let { { Text(it, style = TextStyles.secondary, color = color(mutedColor)) } })
}

private class SheetChoice(val label: String, val filled: Boolean, val onClick: () -> Unit)

@Composable
private fun CallRequestSheet(
    id: String, icon: ImageVector, tone: Color, title: String, scope: String?, mono: Boolean, buttons: List<SheetChoice>, onEnd: () -> Unit,
    modifier: Modifier = Modifier, details: (@Composable ColumnScope.() -> Unit)? = null,
) {
    var open by remember(id) { mutableStateOf(false) }
    Box(modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)).clickable(enabled = false) {}, contentAlignment = Alignment.BottomCenter) {
        Column(
            Modifier.widthIn(max = 560.dp).fillMaxWidth().safeDrawingPadding().padding(Spacing.space8).clip(RoundedCornerShape(28.dp)).background(color(panelColor))
                .semantics { paneTitle = title }.verticalScroll(rememberScrollState()).padding(horizontal = Spacing.space20, vertical = Spacing.space10),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.space12),
        ) {
            Box(Modifier.size(40.dp, 4.dp).clip(PillShape).background(color(mutedColor).copy(alpha = 0.4f)))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("The call is still listening", Modifier.weight(1f), style = TextStyles.caption, color = color(mutedColor))
                Button(onClick = onEnd, style = ButtonStyle.Outlined, shape = PillShape) { Text("End call") }
            }
            Box(Modifier.size(52.dp).clip(RoundedCornerShape(14.dp)).background(tone.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                Icon(icon, Modifier.size(26.dp), tint = tone)
            }
            Text(title, style = TextStyles.title, textAlign = TextAlign.Center)
            scope?.let {
                Text(it, Modifier.fillMaxWidth().clip(PillShape).background(color(backgroundColor)).padding(horizontal = Spacing.space16, vertical = Spacing.space10),
                    style = if (mono) MonoStyle else TextStyles.secondary, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (details != null) {
                Row(Modifier.clip(PillShape).clickable { open = !open }.padding(horizontal = Spacing.space10, vertical = Spacing.space4), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.space4)) {
                    Text(if (open) "Hide details" else "Show details", style = TextStyles.caption, color = color(mutedColor))
                    Icon(if (open) Lucide.ChevronUp else Lucide.ChevronDown, Modifier.size(14.dp), tint = color(mutedColor))
                }
                if (open) Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.space8), content = details)
            }
            Column(Modifier.fillMaxWidth().padding(top = Spacing.space4), verticalArrangement = Arrangement.spacedBy(Spacing.space8)) {
                buttons.forEach { SheetButton(it.label, it.filled, it.onClick) }
            }
        }
    }
}

@Composable
private fun SheetButton(label: String, filled: Boolean, onClick: () -> Unit) {
    val shape = PillShape
    Box(
        Modifier.fillMaxWidth().height(52.dp).clip(shape).background(if (filled) color(onBackgroundColor) else Color.Transparent)
            .then(if (filled) Modifier else Modifier.border(1.dp, color(borderColor), shape)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, style = TextStyles.label, color = if (filled) color(backgroundColor) else color(onBackgroundColor)) }
}

/** The Agents' colours as ARGB by ID, for native call surfaces such as the Live Activity. */
object AgentInks {
    val byId = mutableMapOf<String, Long>()
}
