package kinuvo.ui.design

import kinuvo.app.session.*

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.layout
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.*
import com.composables.ui.components.*
import com.composables.ui.theme.*
import com.composeunstyled.LocalContentColor
import com.composeunstyled.theme.Theme
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.*
import kinuvo.app.markdown.*
import org.intellij.markdown.MarkdownElementTypes
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import kotlin.math.PI
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource
import kotlin.math.sin
import androidx.compose.ui.graphics.Color

/**
 * Chat input edits Markdown source with live formatting and dimmed markers; selecting text shows formatting actions.
 * Enter sends Markdown, Shift+Enter breaks the line.
 * While [running], the send button becomes Stop; while empty, it starts voice chat. Voice buttons are disabled until
 * [onDictate] and [onVoice] are set; while [dictating], Dictate stops it. Typing a [completions] trigger lists matching values to insert. While [blocked] is
 * set, sending is disabled and the reason replaces [hint].
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Composer(
    running: Boolean,
    onSend: (markdown: String) -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Message",
    hint: String? = null,
    state: TextFieldState = rememberTextFieldState(),
    focus: FocusRequester = remember { FocusRequester() },
    completions: List<CompletionSource> = emptyList(),
    onCompletingChange: (Boolean) -> Unit = {},
    controls: @Composable RowScope.() -> Unit = {},
    hasAttachments: Boolean = false,
    sending: Boolean = false,
    canStop: Boolean = true,
    stopWhileSending: Boolean = false,
    attachments: @Composable () -> Unit = {},
    onDictate: (() -> Unit)? = null,
    dictating: Boolean = false,
    onVoice: (() -> Unit)? = null,
    blocked: String? = null,
    containerColor: Color = color(fieldColor),
    /** Drawn under [containerColor], e.g. a blur of what scrolls behind. */
    backdrop: Modifier = Modifier,
    notice: (@Composable () -> Unit)? = null,
    /** While [running], sends the message as the next turn; Enter does this. Null when the Session can't queue. */
    onQueue: ((String) -> Unit)? = null,
    /** While [running], hands the message to the running turn; ⌘/Ctrl+Enter does this. Null when it can't steer. */
    onSteer: ((String) -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val empty by remember(state) { derivedStateOf { state.text.isBlank() } }
    val editorStyles = rememberUpdatedState(markdownEditorStyles())
    val highlights = rememberMarkdownHighlights(state)
    val inputTransformation = remember { InputTransformation {
        if (changes.changeCount == 1) {
            val range = changes.getRange(0)
            val original = changes.getOriginalRange(0)
            if (original.collapsed && range.length == 1 && asCharSequence()[range.start] == '\n' &&
                originalSelection.collapsed && original.start == originalSelection.start && selection.collapsed && selection.start == range.end) {
                asBuffer().continueList()
            }
        }
    } }
    val outputTransformation = remember(highlights) { OutputTransformation {
        highlights.value?.spans?.forEach { span ->
            if (span.start < length) addStyle(editorStyles.value.style(span), span.start, minOf(span.end, length))
        }
    } }
    val query by remember(state, completions) { derivedStateOf { if (completions.isEmpty()) null else findCompletion(state.text.toString(), state.selection, completions) } }
    var dismissedAt by remember { mutableIntStateOf(-1) }
    var highlighted by remember { mutableIntStateOf(0) }
    LaunchedEffect(query?.start, query?.query) {
        highlighted = 0
        if (query == null) dismissedAt = -1
    }
    val open = query?.takeIf { focused && it.start != dismissedAt && it.matches.isNotEmpty() }
    LaunchedEffect(open != null) { onCompletingChange(open != null) }

    fun accept(q: CompletionQuery, item: Completion) {
        val text = q.insertion(item)
        state.edit {
            replace(q.start, q.end, text)
            selection = TextRange(q.start + text.length)
        }
    }

    fun onCompletionKey(event: KeyEvent): Boolean {
        val q = open ?: return false
        val shown = q.matches.take(MaxShown)
        when (event.key) {
            Key.DirectionDown -> highlighted = (highlighted + 1) % shown.size
            Key.DirectionUp -> highlighted = (highlighted - 1 + shown.size) % shown.size
            Key.Enter, Key.Tab -> if (event.isShiftPressed) return false else accept(q, shown[highlighted.coerceIn(shown.indices)])
            Key.Escape -> dismissedAt = q.start
            else -> return false
        }
        return true
    }
    LaunchedEffect(Unit) { if (!hasTouchKeyboard) focus.requestFocus() }

    fun submit() {
        if (blocked != null || (empty && !hasAttachments)) return
        val send = if (running) onQueue ?: return else onSend
        send(state.text.toString().trim())
        state.clearText()
    }

    fun steer(): Boolean {
        val send = onSteer?.takeIf { running && !empty && blocked == null } ?: return false
        send(state.text.toString().trim())
        state.clearText()
        return true
    }

    val shape = RoundedCornerShape(Radius.composer)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.space6)) {
        Column(Modifier.fillMaxWidth()) {
            AnimatedVisibility(notice != null || blocked != null,
                enter = expandVertically(Motion.resizeSpring(), Alignment.Bottom) + fadeIn(tween(Motion.reveal, delayMillis = Motion.revealDelay)),
                exit = shrinkVertically(tween(Motion.exit), Alignment.Bottom) + fadeOut(tween(Motion.fast))) {
                val noticeShape = RoundedCornerShape(Radius.bubble)
                Column(Modifier.padding(bottom = Spacing.space8).fillMaxWidth().clip(noticeShape)
                    .background(color(panelColor)).border(1.dp, color(borderColor), noticeShape)
                    .heightIn(max = 320.dp).verticalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.space14, vertical = Spacing.space10), verticalArrangement = Arrangement.spacedBy(Spacing.space10)) {
                    // Keeps the last content while the panel animates away.
                    var shownBlocked by remember { mutableStateOf(blocked) }
                    var shownNotice by remember { mutableStateOf(notice) }
                    if (blocked != null || notice != null) { shownBlocked = blocked; shownNotice = notice }
                    shownBlocked?.let { Text(it, style = TextStyles.secondary, color = color(mutedColor)) }
                    shownNotice?.invoke()
                }
            }
            Box(Modifier.fillMaxWidth()) {
                val border by animateColorAsState(if (focused) color(accentColor).copy(alpha = 0.55f) else color(borderColor), tween(Motion.standard, easing = Motion.smooth))
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(shape)
                        .then(backdrop)
                        .background(containerColor, shape)
                        .border(1.dp, border, shape)
                        .animateContentSize(Motion.resizeSpring(), Alignment.BottomStart)
                        .padding(Spacing.space8),
                    verticalArrangement = Arrangement.spacedBy(Spacing.space8),
                ) {
                    attachments()
                    Box(Modifier.fillMaxWidth().padding(horizontal = Spacing.space8, vertical = Spacing.space6)) {
                        val style = inputTextStyle()
                        // Leaves at once under the first keystroke; eases back in once the field clears.
                        if (empty) {
                            val shown = remember { Animatable(0f) }
                            LaunchedEffect(Unit) { shown.animateTo(1f, tween(Motion.enter, easing = Motion.smooth)) }
                            Text(placeholder, Modifier.graphicsLayer { alpha = shown.value; translationX = (1f - shown.value) * 6.dp.toPx() }, color = color(mutedColor), style = style)
                        }
                        val platformToolbar = LocalTextToolbar.current
                        val toolbar = remember(platformToolbar) { FocusTapToolbar(platformToolbar) }
                        CompositionLocalProvider(LocalTextToolbar provides if (hasTouchKeyboard) toolbar else platformToolbar) {
                        BasicTextField(
                            state = state,
                            textStyle = style.copy(color = color(onFieldColor)),
                            keyboardOptions = inputKeyboard(capitalize = true),
                            cursorBrush = SolidColor(color(accentColor)),
                            lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 12),
                            inputTransformation = inputTransformation,
                            outputTransformation = outputTransformation,
                            modifier = Modifier.semantics { contentDescription = "Message" }
                                .fillMaxWidth()
                                .heightIn(min = 36.dp)
                                .focusRequester(focus)
                                .onFocusChanged { focused = it.isFocused; toolbar.focused(it.isFocused) }
                                .onPreviewKeyEvent { it.type == KeyEventType.KeyDown && (onCompletionKey(it) || onKey(it, state, ::submit, ::steer)) },
                        )
                        }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.space8)) {
                        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.space8), content = controls)
                        VoiceButton(Lucide.Mic, if (dictating) "Stop dictating" else "Dictate", onDictate, dictating)
                        val action = when {
                            sending && !stopWhileSending -> Action.Sending
                            running -> Action.Stop
                            empty && !hasAttachments -> Action.Voice
                            else -> Action.Send
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ActionButton(action, when (action) {
                                Action.Sending -> false
                                Action.Stop -> canStop
                                Action.Voice -> onVoice != null
                                Action.Send -> blocked == null
                            }) {
                                when (action) {
                                    Action.Sending -> {}
                                    Action.Stop -> onStop()
                                    Action.Voice -> onVoice?.invoke()
                                    Action.Send -> submit()
                                }
                            }
                            // Typing during a run offers to queue it (Enter) or steer the run with it; the pill grows out of Stop.
                            // Clipped to a capsule as it grows, so it unrolls as a pill rather than behind a hard edge.
                            val queueing = action == Action.Stop && !empty && onQueue != null
                            AnimatedVisibility(queueing, Modifier.clip(CircleShape),
                                enter = expandHorizontally(Motion.revealSpring(), Alignment.Start) + fadeIn(tween(Motion.enter)) +
                                    scaleIn(Motion.morphSpring(), initialScale = 0.7f, transformOrigin = TransformOrigin(0f, 0.5f)),
                                exit = shrinkHorizontally(tween(Motion.exit, easing = Motion.smooth), Alignment.Start) + fadeOut(tween(Motion.fast)) +
                                    scaleOut(tween(Motion.exit), targetScale = 0.7f, transformOrigin = TransformOrigin(0f, 0.5f))) {
                                QueueButton(::submit, onSteer?.let { { steer(); Unit } }, queueing && blocked == null, Modifier.padding(start = Spacing.space8))
                            }
                        }
                    }
                }
                // Floats above the input so showing it doesn't move the text being selected.
                var shownQuery by remember { mutableStateOf(open) }
                open?.let { shownQuery = it }
                androidx.compose.animation.AnimatedVisibility(open != null, Modifier.align(Alignment.TopStart).above(6.dp),
                    enter = fadeIn(tween(Motion.fast)) + scaleIn(Motion.morphSpring(), initialScale = 0.94f, transformOrigin = TransformOrigin(0.1f, 1f)) + slideInVertically(Motion.morphSpring()) { it / 10 },
                    exit = fadeOut(tween(Motion.fast)) + scaleOut(tween(Motion.exit), targetScale = 0.97f, transformOrigin = TransformOrigin(0.1f, 1f))) {
                    shownQuery?.let { q -> CompletionList(q, highlighted, onPick = { item -> open?.let { accept(it, item) } }) }
                }
                FormatBar(state, highlights, focused, Modifier.align(Alignment.TopStart).offset(x = 8.dp, y = (-44).dp))
            }
        }
        val shownHint = hint?.takeIf { blocked == null }
        AnimatedVisibility(shownHint != null, enter = fadeIn(tween(Motion.reveal)) + expandVertically(Motion.resizeSpring()), exit = fadeOut(tween(Motion.fast)) + shrinkVertically(tween(Motion.exit))) {
            var last by remember { mutableStateOf(shownHint.orEmpty()) }
            shownHint?.let { last = it }
            Text(last, Modifier.fillMaxWidth(), style = TextStyles.overline, color = color(mutedColor), textAlign = TextAlign.Center)
        }
    }
}

/** Sends during a run: the pill queues the message as the next turn; its menu can steer the running turn instead. */
@Composable
private fun QueueButton(onQueue: () -> Unit, onSteer: (() -> Unit)?, enabled: Boolean, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    LaunchedEffect(enabled) { if (!enabled) open = false }
    val pill = modifier.height(40.dp).clip(CircleShape).background(color(accentColor))
    Row(pill, verticalAlignment = Alignment.CenterVertically) {
        Hint("Queue as the next message (Enter)", TooltipSide.Top) {
            Row(Modifier.fillMaxHeight().clickable(enabled = enabled, role = Role.Button, onClick = onQueue).padding(start = Spacing.space14, end = if (onSteer == null) Spacing.space14 else Spacing.space8),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.space6)) {
                Icon(Lucide.ListPlus, Modifier.size(16.dp), tint = color(onAccentColor))
                Text("Queue", style = TextStyles.label, fontWeight = FontWeight.SemiBold, color = color(onAccentColor), singleLine = true)
            }
        }
        if (onSteer != null) DropdownMenu(expanded = open && enabled, onExpandedChange = { open = it }, panel = {
            DropdownMenuPanel {
                DropdownMenuItem(onClick = { open = false; if (enabled) onQueue() }) {
                    Column {
                        Text("Queue")
                        Text("Runs after this turn · Enter", style = TextStyles.caption, color = color(mutedColor))
                    }
                }
                DropdownMenuItem(onClick = { open = false; if (enabled) onSteer() }) {
                    Column {
                        Text("Steer now")
                        Text("Joins the turn in progress · ${shortcut("Enter")}", style = TextStyles.caption, color = color(mutedColor))
                    }
                }
            }
        }) {
            Row(Modifier.height(40.dp)) {
                Box(Modifier.fillMaxHeight().width(1.dp).background(color(onAccentColor).copy(alpha = 0.3f)))
                Box(Modifier.fillMaxHeight().clickable(enabled = enabled, role = Role.Button) { open = true }.padding(horizontal = Spacing.space8)
                    .semantics { contentDescription = "More ways to send" }, contentAlignment = Alignment.Center) {
                    Icon(Lucide.ChevronDown, Modifier.size(16.dp), tint = color(onAccentColor))
                }
            }
        }
    }
}

@Composable
private fun VoiceButton(icon: ImageVector, label: String, onClick: (() -> Unit)?, active: Boolean = false) {
    val fill by animateColorAsState(color(accentColor).copy(alpha = if (active) 0.15f else 0f), Motion.morphSpring())
    val tint by animateColorAsState(color(if (active) accentColor else onFieldColor), Motion.morphSpring())
    IconButton(onClick = { onClick?.invoke() }, enabled = onClick != null, modifier = Modifier.size(40.dp).background(fill, CircleShape), style = ButtonStyle.Ghost, shape = CircleShape,
        contentColor = tint) {
        Box(Modifier.semantics { contentDescription = if (onClick != null || active) label else "$label (coming soon)" }, contentAlignment = Alignment.Center) {
            AnimatedContent(active, transitionSpec = { morph() }, contentAlignment = Alignment.Center) { on ->
                if (on) VoiceBars(active = true) else Icon(icon, Modifier.size(22.dp), contentDescription = null)
            }
        }
    }
}

private enum class Action { Sending, Stop, Voice, Send }

/** The composer's main button: one circle whose fill and glyph morph between its jobs instead of swapping buttons. */
@Composable
private fun ActionButton(action: Action, enabled: Boolean, onClick: () -> Unit) {
    val label = when (action) {
        Action.Sending -> "Sending"
        Action.Stop -> "Stop"
        Action.Voice -> if (enabled) "Voice chat" else "Voice chat (coming soon)"
        Action.Send -> "Send"
    }
    val accent = action == Action.Send || action == Action.Voice
    val fill by animateColorAsState(color(if (accent) accentColor else secondaryColor), Motion.morphSpring())
    val tint by animateColorAsState(color(if (accent) onAccentColor else onSecondaryColor).copy(alpha = if (enabled || action == Action.Sending) 1f else 0.45f), Motion.morphSpring())
    val press = remember { MutableInteractionSource() }
    Box(Modifier.size(40.dp).pressScale(press).clip(CircleShape).background(fill)
        .clickable(press, LocalIndication.current, enabled = enabled, role = Role.Button, onClick = onClick)
        .semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        CompositionLocalProvider(LocalContentColor provides tint) {
            AnimatedContent(action, transitionSpec = {
                // Sending lifts the arrow away; everything else turns in place.
                if (initialState == Action.Send && targetState != Action.Voice) morph(slideOut = true) else morph()
            }, contentAlignment = Alignment.Center) { a ->
                when (a) {
                    Action.Sending -> Spinner(size = 18.dp, tint = tint)
                    Action.Stop -> Icon(Lucide.Square, Modifier.size(16.dp), contentDescription = null)
                    Action.Voice -> VoiceBars()
                    Action.Send -> Icon(Lucide.ArrowUp, Modifier.size(22.dp), contentDescription = null)
                }
            }
        }
    }
}

private fun <S> AnimatedContentTransitionScope<S>.morph(slideOut: Boolean = false): ContentTransform {
    val enter = fadeIn(tween(Motion.fast)) + scaleIn(Motion.morphSpring(), initialScale = 0.5f)
    val exit = fadeOut(tween(Motion.immediate)) + if (slideOut) slideOutVertically(tween(Motion.exit, easing = Motion.smooth)) { -it } else scaleOut(tween(Motion.fast, easing = Motion.smooth), targetScale = 0.5f)
    return (enter togetherWith exit).using(SizeTransform(clip = false))
}

/** Gives a press a soft squeeze. */
@Composable
private fun Modifier.pressScale(source: MutableInteractionSource): Modifier {
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.9f else 1f, Motion.pressSpring())
    return graphicsLayer { scaleX = scale; scaleY = scale }
}

/** Audio level bars, the voice chat glyph; they bounce while [active]. */
@Composable
internal fun VoiceBars(modifier: Modifier = Modifier, active: Boolean = false) {
    val tint = LocalContentColor.current
    val phase = if (active) rememberInfiniteTransition().animateFloat(0f, 2 * PI.toFloat(), infiniteRepeatable(tween(Motion.spin, easing = Motion.linear))).value else null
    Canvas(modifier.size(20.dp)) {
        val bar = 2.2.dp.toPx()
        listOf(0.35f, 0.75f, 1f, 0.75f, 0.35f).forEachIndexed { i, h ->
            val x = size.width * (i + 0.5f) / 5
            val half = size.height * (phase?.let { 0.3f + 0.7f * h * (0.5f + 0.5f * sin(it + i * 1.3f)) } ?: h) / 2
            drawLine(tint, Offset(x, center.y - half), Offset(x, center.y + half), bar, StrokeCap.Round)
        }
    }
}

/** Places the content above the parent's top edge without taking up space. */
private fun Modifier.above(gap: Dp) = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints.copy(minHeight = 0))
    layout(placeable.width, 0) { placeable.place(0, -placeable.height - gap.roundToPx()) }
}

private fun onKey(event: KeyEvent, state: TextFieldState, submit: () -> Unit, steer: () -> Boolean): Boolean {
    if (event.key == Key.Enter && event.isShortcutPressed && steer()) return true
    if (event.key == Key.Enter && !event.isShiftPressed) {
        submit()
        return true
    }
    if (!event.isShortcutPressed) return false
    when (event.key) {
        Key.B -> state.edit { asBuffer().toggle(MarkdownElementTypes.STRONG) }
        Key.I -> state.edit { asBuffer().toggle(MarkdownElementTypes.EMPH) }
        Key.E -> state.edit { asBuffer().toggle(MarkdownElementTypes.CODE_SPAN) }
        else -> return false
    }
    return true
}

@Composable
private fun FormatBar(state: TextFieldState, highlights: State<MarkdownHighlights?>, focused: Boolean, modifier: Modifier) = AnimatedVisibility(focused && !state.selection.collapsed, modifier,
    enter = fadeIn(tween(Motion.fast)) + scaleIn(Motion.morphSpring(), initialScale = 0.9f, transformOrigin = TransformOrigin(0.1f, 1f)) + slideInVertically(Motion.morphSpring()) { it / 4 },
    exit = fadeOut(tween(Motion.fast)) + scaleOut(tween(Motion.exit), targetScale = 0.95f, transformOrigin = TransformOrigin(0.1f, 1f))) {
    val highlights = highlights.value
    Panel(shape = Theme[shapes][mediumShape], contentPadding = PaddingValues(Spacing.space4)) {
        val active = if (highlights != null && highlights.text.contentEquals(state.text)) highlights.activeTypes(state.selection.min, state.selection.max) else emptySet()
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.space2)) {
            FormatToggle(Lucide.Bold, "Bold ${shortcut("B")}", MarkdownElementTypes.STRONG in active) { state.edit { asBuffer().toggle(MarkdownElementTypes.STRONG) } }
            FormatToggle(Lucide.Italic, "Italic ${shortcut("I")}", MarkdownElementTypes.EMPH in active) { state.edit { asBuffer().toggle(MarkdownElementTypes.EMPH) } }
            FormatToggle(Lucide.Code, "Code ${shortcut("E")}", MarkdownElementTypes.CODE_SPAN in active) { state.edit { asBuffer().toggle(MarkdownElementTypes.CODE_SPAN) } }
            FormatToggle(Lucide.List, "Bulleted list", MarkdownElementTypes.UNORDERED_LIST in active) { state.edit { asBuffer().toggleList(false) } }
            FormatToggle(Lucide.ListOrdered, "Numbered list", MarkdownElementTypes.ORDERED_LIST in active) { state.edit { asBuffer().toggleList(true) } }
        }
    }
}

@Composable
private fun FormatToggle(icon: ImageVector, label: String, active: Boolean, onClick: () -> Unit) {
    Hint(label) {
        IconButton(
            onClick = onClick,
            modifier = Modifier.size(28.dp).focusProperties { canFocus = false },
            style = if (active) ButtonStyle.Secondary else ButtonStyle.Ghost,
            shape = Theme[shapes][smallShape],
            contentColor = if (active) color(onSecondaryColor) else color(mutedColor),
        ) {
            Icon(icon, Modifier.size(14.dp), contentDescription = label)
        }
    }
}

/** Drops cursor-only menus (Paste) for 600ms after the field gains focus, covering the focusing tap; later taps or a long press still show it. */
private class FocusTapToolbar(private val platform: TextToolbar) : TextToolbar {
    private var focusedAt: TimeSource.Monotonic.ValueTimeMark? = null

    fun focused(focused: Boolean) { focusedAt = if (focused) TimeSource.Monotonic.markNow() else null }

    override val status get() = platform.status
    override fun hide() = platform.hide()
    override fun showMenu(rect: Rect, onCopyRequested: (() -> Unit)?, onPasteRequested: (() -> Unit)?, onCutRequested: (() -> Unit)?, onSelectAllRequested: (() -> Unit)?) {
        val cursorOnly = onCopyRequested == null && onCutRequested == null
        if (cursorOnly && focusedAt?.let { it.elapsedNow() < 600.milliseconds } == true) return
        platform.showMenu(rect, onCopyRequested, onPasteRequested, onCutRequested, onSelectAllRequested)
    }
}
