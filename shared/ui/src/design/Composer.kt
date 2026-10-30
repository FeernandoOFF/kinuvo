package kinuvo.ui.design

import kinuvo.app.session.*

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import com.mohamedrejeb.richeditor.model.RichTextState
import com.mohamedrejeb.richeditor.model.rememberRichTextState
import kotlin.math.PI
import kotlin.math.sin
import com.mohamedrejeb.richeditor.ui.BasicRichTextEditor

/**
 * Chat input on compose-rich-editor. Markdown typed inline (`**bold**`, `*italic*`, `` `code` ``, `- ` lists) turns into
 * formatting; selecting text shows formatting actions. Enter sends Markdown, Shift+Enter breaks the line.
 * While [running], the send button becomes Stop; while empty, it starts voice chat. Voice buttons are disabled until
 * [onDictate] and [onVoice] are set; while [dictating], Dictate stops it. Typing a [completions] trigger lists matching values to insert. While [blocked] is
 * set, sending is disabled and the reason replaces [hint].
 */
@Composable
fun Composer(
    running: Boolean,
    onSend: (markdown: String) -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Message",
    hint: String? = null,
    state: RichTextState = rememberRichTextState(),
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
    containerColor: androidx.compose.ui.graphics.Color = color(fieldColor),
    /** Drawn under [containerColor], e.g. a blur of what scrolls behind. */
    backdrop: Modifier = Modifier,
    notice: (@Composable () -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val empty by remember(state) { derivedStateOf { state.annotatedString.text.isBlank() } }
    val code = color(subtleColor)
    val border = color(borderColor)
    LaunchedEffect(code, border) {
        state.config.codeSpanBackgroundColor = code
        state.config.codeSpanStrokeColor = border
        state.config.codeSpanCornerRadius = 4.sp
    }
    val query by remember(state, completions) { derivedStateOf { findCompletion(state.annotatedString.text, state.selection, completions) } }
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
        state.replaceTextRange(TextRange(q.start, q.end), text)
        state.selection = TextRange(q.start + text.length)
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
    LaunchedEffect(state) {
        var previous = state.annotatedString.text
        snapshotFlow { state.annotatedString.text }.collect { text ->
            val at = state.selection.start - 1
            if (text.length == previous.length + 1 && at >= 0 && isInlineMarker(text[at])) state.applyInlineMarkdown()
            previous = state.annotatedString.text
        }
    }

    fun submit() {
        if (running || blocked != null || (empty && !hasAttachments)) return
        onSend(state.toMarkdown().trim())
        state.clear()
    }

    val shape = RoundedCornerShape(28.dp)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Column(Modifier.fillMaxWidth()) {
            if (notice != null || blocked != null) {
                val noticeShape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(noticeShape)
                    .background(color(panelColor)).border(1.dp, color(borderColor), noticeShape)
                    .heightIn(max = 320.dp).verticalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    blocked?.let { Text(it, fontSize = 13.sp, color = color(mutedColor)) }
                    notice?.invoke()
                }
            }
            Box(Modifier.fillMaxWidth()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(shape)
                        .then(backdrop)
                        .background(containerColor, shape)
                        .border(1.dp, if (focused) color(accentColor).copy(alpha = 0.55f) else color(borderColor), shape)
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    attachments()
                    Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
                        val style = inputTextStyle()
                        if (empty) Text(placeholder, color = color(mutedColor), style = style)
                        BasicRichTextEditor(
                            state = state,
                            textStyle = style.copy(color = color(onFieldColor)),
                            keyboardOptions = inputKeyboard(capitalize = true),
                            cursorBrush = SolidColor(color(accentColor)),
                            maxLines = 12,
                            modifier = Modifier.semantics { contentDescription = "Message" }
                                .fillMaxWidth()
                                .heightIn(min = 36.dp)
                                .focusRequester(focus)
                                .onFocusChanged { focused = it.isFocused }
                                .onPreviewKeyEvent { it.type == KeyEventType.KeyDown && (onCompletionKey(it) || onKey(it, state, ::submit)) },
                        )
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), content = controls)
                        VoiceButton(Lucide.Mic, if (dictating) "Stop dictating" else "Dictate", onDictate, dictating)
                        val accent = Modifier.size(40.dp).background(color(accentColor), CircleShape)
                        if (sending && !stopWhileSending) {
                            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { Spinner(size = 18.dp) }
                        } else if (running) {
                            IconButton(onClick = onStop, enabled = canStop, modifier = Modifier.size(40.dp), style = ButtonStyle.Secondary, shape = CircleShape) {
                                Icon(Lucide.Square, Modifier.size(18.dp), contentDescription = "Stop")
                            }
                        } else if (empty && !hasAttachments) {
                            IconButton(onClick = { onVoice?.invoke() }, enabled = onVoice != null, modifier = accent, style = ButtonStyle.Ghost, shape = CircleShape, contentColor = color(onAccentColor)) {
                                VoiceBars(Modifier.semantics { contentDescription = if (onVoice != null) "Voice chat" else "Voice chat (coming soon)" })
                            }
                        } else {
                            IconButton(onClick = ::submit, enabled = blocked == null, modifier = accent, style = ButtonStyle.Ghost, shape = CircleShape, contentColor = color(onAccentColor)) {
                                Icon(Lucide.ArrowUp, Modifier.size(22.dp), contentDescription = "Send")
                            }
                        }
                    }
                }
                // Floats above the input so showing it doesn't move the text being selected.
                open?.let { q ->
                    CompletionList(q, highlighted, onPick = { accept(q, it) }, Modifier.align(Alignment.TopStart).above(6.dp))
                }
                FormatBar(state, focused && !state.selection.collapsed, Modifier.align(Alignment.TopStart).offset(x = 8.dp, y = (-44).dp))
            }
        }
        hint?.takeIf { blocked == null }?.let { Text(it, Modifier.fillMaxWidth(), fontSize = 11.sp, color = color(mutedColor), textAlign = TextAlign.Center) }
    }
}

@Composable
private fun VoiceButton(icon: ImageVector, label: String, onClick: (() -> Unit)?, active: Boolean = false) {
    val background = if (active) Modifier.background(color(accentColor).copy(alpha = 0.15f), CircleShape) else Modifier
    IconButton(onClick = { onClick?.invoke() }, enabled = onClick != null, modifier = Modifier.size(40.dp).then(background), style = ButtonStyle.Ghost, shape = CircleShape,
        contentColor = color(if (active) accentColor else onFieldColor)) {
        if (active) VoiceBars(active = true) else Icon(icon, Modifier.size(22.dp), contentDescription = if (onClick != null) label else "$label (coming soon)")
    }
}

/** Audio level bars, the voice chat glyph; they bounce while [active]. */
@Composable
internal fun VoiceBars(modifier: Modifier = Modifier, active: Boolean = false) {
    val tint = LocalContentColor.current
    val phase = if (active) rememberInfiniteTransition().animateFloat(0f, 2 * PI.toFloat(), infiniteRepeatable(tween(900, easing = LinearEasing))).value else null
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

private fun onKey(event: KeyEvent, state: RichTextState, submit: () -> Unit): Boolean {
    if (event.key == Key.Enter && !event.isShiftPressed) {
        submit()
        return true
    }
    if (!event.isShortcutPressed) return false
    when (event.key) {
        Key.B -> state.toggleSpanStyle(Bold)
        Key.I -> state.toggleSpanStyle(Italic)
        Key.E -> state.toggleCodeSpan()
        else -> return false
    }
    return true
}

@Composable
private fun FormatBar(state: RichTextState, visible: Boolean, modifier: Modifier) = AnimatedVisibility(visible, modifier, enter = fadeIn(), exit = fadeOut()) {
    Panel(shape = Theme[shapes][mediumShape], contentPadding = PaddingValues(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            FormatToggle(Lucide.Bold, "Bold ${shortcut("B")}", state.currentSpanStyle.fontWeight == Bold.fontWeight) { state.toggleSpanStyle(Bold) }
            FormatToggle(Lucide.Italic, "Italic ${shortcut("I")}", state.currentSpanStyle.fontStyle == Italic.fontStyle) { state.toggleSpanStyle(Italic) }
            FormatToggle(Lucide.Code, "Code ${shortcut("E")}", state.isCodeSpan) { state.toggleCodeSpan() }
            FormatToggle(Lucide.List, "Bulleted list", state.isUnorderedList) { state.toggleUnorderedList() }
            FormatToggle(Lucide.ListOrdered, "Numbered list", state.isOrderedList) { state.toggleOrderedList() }
        }
    }
}

@Composable
private fun FormatToggle(icon: ImageVector, label: String, active: Boolean, onClick: () -> Unit) {
    Tooltip(panel = { TooltipPanel { Text(label, fontSize = 12.sp) } }) {
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
