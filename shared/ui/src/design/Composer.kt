package kinuvo.ui.design

import kinuvo.app.session.*

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import kotlin.math.PI
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
) {
    var focused by remember { mutableStateOf(false) }
    val empty by remember(state) { derivedStateOf { state.text.isBlank() } }
    val editorStyles = markdownEditorStyles()
    val highlights = rememberMarkdownHighlights(state)
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
        if (running || blocked != null || (empty && !hasAttachments)) return
        onSend(state.text.toString().trim())
        state.clearText()
    }

    val shape = RoundedCornerShape(Radius.composer)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.space6)) {
        Column(Modifier.fillMaxWidth()) {
            if (notice != null || blocked != null) {
                val noticeShape = RoundedCornerShape(Radius.bubble)
                Column(Modifier.fillMaxWidth().clip(noticeShape)
                    .background(color(panelColor)).border(1.dp, color(borderColor), noticeShape)
                    .heightIn(max = 320.dp).verticalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.space14, vertical = Spacing.space10), verticalArrangement = Arrangement.spacedBy(Spacing.space10)) {
                    blocked?.let { Text(it, style = TextStyles.secondary, color = color(mutedColor)) }
                    notice?.invoke()
                }
            }
            if (notice != null || blocked != null) Spacer(Modifier.height(Spacing.space8))
            Box(Modifier.fillMaxWidth()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(shape)
                        .then(backdrop)
                        .background(containerColor, shape)
                        .border(1.dp, if (focused) color(accentColor).copy(alpha = 0.55f) else color(borderColor), shape)
                        .padding(Spacing.space8),
                    verticalArrangement = Arrangement.spacedBy(Spacing.space8),
                ) {
                    attachments()
                    Box(Modifier.fillMaxWidth().padding(horizontal = Spacing.space8, vertical = Spacing.space6)) {
                        val style = inputTextStyle()
                        if (empty) Text(placeholder, color = color(mutedColor), style = style)
                        BasicTextField(
                            state = state,
                            textStyle = style.copy(color = color(onFieldColor)),
                            keyboardOptions = inputKeyboard(capitalize = true),
                            cursorBrush = SolidColor(color(accentColor)),
                            lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 12),
                            inputTransformation = {
                                if (changes.changeCount == 1) {
                                    val range = changes.getRange(0)
                                    val original = changes.getOriginalRange(0)
                                    if (original.collapsed && range.length == 1 && asCharSequence()[range.start] == '\n' &&
                                        originalSelection.collapsed && original.start == originalSelection.start && selection.collapsed && selection.start == range.end) {
                                        asBuffer().continueList()
                                    }
                                }
                            },
                            outputTransformation = {
                                highlights.value?.takeIf { it.text.contentEquals(asCharSequence()) }?.spans?.forEach { span ->
                                    addStyle(editorStyles.style(span), span.start, span.end)
                                }
                            },
                            modifier = Modifier.semantics { contentDescription = "Message" }
                                .fillMaxWidth()
                                .heightIn(min = 36.dp)
                                .focusRequester(focus)
                                .onFocusChanged { focused = it.isFocused }
                                .onPreviewKeyEvent { it.type == KeyEventType.KeyDown && (onCompletionKey(it) || onKey(it, state, ::submit)) },
                        )
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.space8)) {
                        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.space8), content = controls)
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
                FormatBar(state, highlights.value, focused && !state.selection.collapsed, Modifier.align(Alignment.TopStart).offset(x = 8.dp, y = (-44).dp))
            }
        }
        hint?.takeIf { blocked == null }?.let { Text(it, Modifier.fillMaxWidth(), style = TextStyles.overline, color = color(mutedColor), textAlign = TextAlign.Center) }
    }
}

@Composable
private fun VoiceButton(icon: ImageVector, label: String, onClick: (() -> Unit)?, active: Boolean = false) {
    val background = if (active) Modifier.background(color(accentColor).copy(alpha = 0.15f), CircleShape) else Modifier
    IconButton(onClick = { onClick?.invoke() }, enabled = onClick != null, modifier = Modifier.size(40.dp).then(background), style = ButtonStyle.Ghost, shape = CircleShape,
        contentColor = color(if (active) accentColor else onFieldColor)) {
        // The bars replace the icon while active, so they carry its label.
        if (active) VoiceBars(Modifier.semantics { contentDescription = label }, active = true) else Icon(icon, Modifier.size(22.dp), contentDescription = if (onClick != null) label else "$label (coming soon)")
    }
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

private fun onKey(event: KeyEvent, state: TextFieldState, submit: () -> Unit): Boolean {
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
private fun FormatBar(state: TextFieldState, highlights: MarkdownHighlights?, visible: Boolean, modifier: Modifier) = AnimatedVisibility(visible, modifier, enter = fadeIn(), exit = fadeOut()) {
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
