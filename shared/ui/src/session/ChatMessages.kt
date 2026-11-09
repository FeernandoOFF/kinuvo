package kinuvo.ui.session

import kinuvo.ui.surface.LocalSurfaceItems

import kinuvo.ui.surface.LocalSurfaceRenderers
import kinuvo.ui.surface.SurfaceCall
import kinuvo.ui.surface.HtmlSurface
import kinuvo.ui.surface.McpAppSurface
import kinuvo.ui.surface.LocalHtmlHeights
import kinuvo.ui.surface.LocalHtmlSurfaces
import kinuvo.ui.surface.SurfaceRenderer
import kinuvo.protocol.Attachment
import kinuvo.app.connection.*
import kinuvo.app.session.*
import kinuvo.app.agent.*
import kinuvo.ui.agent.*
import kinuvo.app.navigation.*
import kinuvo.ui.board.*
import kinuvo.ui.routine.*
import kinuvo.ui.navigation.*
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.scaleIn
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.shadow
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.ui.input.key.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.*
import com.composables.ui.components.*
import com.composables.ui.theme.*
import com.composeunstyled.LocalTextStyle
import com.composeunstyled.theme.Theme
import kinuvo.ui.design.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.builtins.ListSerializer
import kinuvo.protocol.ProtocolJson
import kinuvo.protocol.Question
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.launch
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import kinuvo.protocol.RequestMetrics
import kotlinx.serialization.json.JsonElement

@Composable
internal fun Messages(
    state: ChatState,
    listState: LazyListState,
    follow: MutableState<Boolean>,
    modifier: Modifier,
    actions: ChatActions,
    /** Room under the last message for the composer floating over it. */
    bottom: Dp = Spacing.none,
    /** Room above the first message for bubbles floating over it. */
    top: Dp = Spacing.none,
) {
    var following by follow
    val items = state.items
    val rows = remember(items) { items.rows() }
    val finished = remember(items, state.running) { items.finishedReplies(state.running) }
    val last = items.lastOrNull()
    // A trailing group of steps shows the run's progress itself.
    val thinking = state.running && last !is ChatItem.Assistant && !(last is ChatItem.ToolCall && last.waiting) && rows.lastOrNull() !is ChatRow.Steps
    val writing = state.running && last is ChatItem.Assistant && state.requests.isEmpty()
    val content = Triple(items.size, (last as? ChatItem.Assistant)?.text?.length ?: (last as? ChatItem.Reasoning)?.text?.length, thinking)
    var seen by remember { mutableStateOf(content) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(listState) {
        var previous = listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        snapshotFlow { Triple(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset, listState.canScrollForward) }
            .collect { (index, offset, canScrollForward) ->
                val up = index < previous.first || (index == previous.first && offset < previous.second)
                if (up && listState.isScrollInProgress && canScrollForward) following = false
                else if (!canScrollForward && listState.layoutInfo.totalItemsCount > 0) following = true
                previous = index to offset
            }
    }
    LaunchedEffect(content, following) {
        if (following) {
            seen = content
            if (rows.isNotEmpty() || thinking) {
                listState.scrollToItem((rows.size - if (thinking) 0 else 1).coerceAtLeast(0))
                listState.scrollBy(100_000f)
            }
        }
    }
    LaunchedEffect(listState, state.hasOlder) {
        snapshotFlow { listState.firstVisibleItemIndex == 0 && listState.layoutInfo.totalItemsCount > 0 }
            .collect { atTop -> if (atTop && state.hasOlder) actions.onLoadOlder() }
    }
    if (items.isEmpty()) {
        // Until the first catch-up there's nothing to say yet; the empty state is only for Sessions that are empty.
        if (state.loaded) EmptyState(state, modifier.padding(top = top, bottom = bottom)) else Box(modifier.fillMaxWidth())
        return
    }
    val surfaceRenderers = remember(state.selected) { mutableMapOf<String, SurfaceRenderer>() }
    val htmlHeights = remember(state.selected) { mutableMapOf<String, Int>() }
    val htmlSurfaces = remember(state.selected) { mutableStateListOf<String>() }
    CompositionLocalProvider(LocalHtmlSurfaces provides htmlSurfaces, LocalHtmlHeights provides htmlHeights, LocalSurfaceRenderers provides surfaceRenderers, LocalSurfaceItems provides items) {
        Box(modifier.fillMaxWidth()) {
            SelectionContainer {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    state = listState,
                    contentPadding = PaddingValues(start = Spacing.space16, top = Spacing.space20 + top, end = Spacing.space16, bottom = Spacing.space20 + bottom),
                    verticalArrangement = Arrangement.spacedBy(Spacing.space14),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    items(rows, key = { it.key }, contentType = {
                        when (it) {
                            is ChatRow.Single -> it.item::class
                            is ChatRow.Steps -> ChatRow.Steps::class
                        }
                    }) { row ->
                        // A sent message replaces its outgoing copy in place, so only the outgoing one animates in.
                        val user = (row as? ChatRow.Single)?.item is ChatItem.User
                        Box(Modifier.animateItem(fadeInSpec = if (user) null else tween(Motion.item), fadeOutSpec = null).widthIn(max = ContentWidth).fillMaxWidth()) {
                            when (row) {
                                is ChatRow.Single -> if (row.key == "outgoing") Entering { ChatItemView(row.item, actions) }
                                    else ChatItemView(row.item, actions, state.voice?.spoken?.get(row.item.id), writing = writing && row.item.id == last?.id, finished = row.item.id in finished)
                                is ChatRow.Steps -> StepsView(row, active = state.running && row === rows.last(), actions = actions)
                            }
                        }
                    }
                    state.voice?.heard?.takeIf { it.isNotBlank() }?.let { heard ->
                        item(key = "heard") { Box(Modifier.animateItem(fadeOutSpec = null).widthIn(max = ContentWidth).fillMaxWidth().alpha(0.6f)) { Entering { ChatItemView(ChatItem.User("heard", heard), actions) } } }
                    }
                    if (thinking || writing) item(key = "thinking") {
                        Row(Modifier.widthIn(max = ContentWidth).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.space8)) {
                            Spinner(tint = color(accentColor))
                            Text(if (writing) "Writing…" else "Working…", color = color(mutedColor), style = TextStyles.secondary)
                        }
                    }
                }
            }
            // Touch screens scroll by swiping; estimating mixed-height rows there only made the bar jump.
            if (LocalInteractionMode.current != InteractionMode.Touch)
                VerticalScrollbar(rememberVerticalScrollbarState(listState), Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(bottom = bottom))
            AnimatedVisibility(
                !following && listState.canScrollForward,
                // At the edge, over the margin rather than the text.
                Modifier.align(Alignment.BottomEnd).padding(end = Spacing.space12, bottom = Spacing.space12 + bottom),
                enter = fadeIn() + slideInVertically { it / 2 },
                exit = fadeOut() + slideOutVertically { it / 2 },
            ) {
                JumpToBottom(unseen = content != seen) {
                    scope.launch {
                        listState.animateScrollToItem((rows.size - if (thinking) 0 else 1).coerceAtLeast(0))
                        following = true
                    }
                }
            }
        }
    }
}

@Composable
internal fun DropOverlay() {
    val shape = Theme[shapes][largeShape]
    Box(Modifier.fillMaxSize().padding(Spacing.space12).background(color(backgroundColor).copy(alpha = 0.85f), shape).border(2.dp, color(accentColor).copy(alpha = 0.6f), shape), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.space8)) {
            Icon(Lucide.Paperclip, Modifier.size(28.dp), tint = color(accentColor))
            Text("Drop to attach", style = TextStyles.callout)
            Text("4 files · 10 MB each", style = TextStyles.caption, color = color(mutedColor))
        }
    }
}

@Composable
private fun Entering(content: @Composable () -> Unit) {
    val shown = remember { MutableTransitionState(false) }.apply { targetState = true }
    AnimatedVisibility(shown, enter = fadeIn(tween(Motion.enter)) + slideInVertically(Motion.appearSpring()) { it }
        + scaleIn(initialScale = 0.92f, transformOrigin = TransformOrigin(1f, 1f))) { content() }
}

@Composable
private fun JumpToBottom(unseen: Boolean, onClick: () -> Unit) {
    val shape = PillShape
    Row(
        Modifier.height(40.dp).widthIn(min = 40.dp).shadow(6.dp, shape).clip(shape).background(color(panelColor)).border(1.dp, color(borderColor), shape)
            .clickable(onClick = onClick).padding(horizontal = if (unseen) Spacing.space14 else Spacing.none),
        horizontalArrangement = Arrangement.spacedBy(Spacing.space6, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Lucide.ArrowDown, Modifier.size(16.dp), contentDescription = "Jump to the latest")
        if (unseen) Text("New messages", style = TextStyles.caption)
    }
}

@Composable
internal fun ChatItemView(item: ChatItem, actions: ChatActions, spoken: Int? = null, writing: Boolean = false, finished: Boolean = false) {
    when (item) {
        is ChatItem.User -> if (item.routine != null) Expandable(Lucide.AlarmClock, requireNotNull(item.routine), subtitle = "routine") { Markdown(item.text) }
        else if (item.action != null) SurfaceActionLine(item.text)
        else if (item.task != null) Expandable(Lucide.SquareKanban, requireNotNull(item.task), subtitle = "task") { Markdown(item.text) }
        // Inset from the start, so long messages still read as the user's rather than as cards.
        else Column(Modifier.fillMaxWidth().padding(start = Spacing.space48), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(Spacing.space4)) {
            Panel(
                Modifier.widthIn(max = 560.dp),
                color = color(selectedControlColor),
                borderColor = Color.Transparent,
                shape = Theme[shapes][largeShape],
                contentPadding = PaddingValues(horizontal = Spacing.space14, vertical = Spacing.space10),
            ) { Column(verticalArrangement = Arrangement.spacedBy(Spacing.space8)) {
                if (item.text.isNotBlank()) Markdown(item.text)
                item.attachments.forEach { a ->
                    LaunchedEffect(a.id) { if (a.mimeType.startsWith("image/")) actions.onLoadAttachment(a.id) }
                    AttachmentChip(a, actions.downloads[a.id]?.base64, onPreview = { actions.onPreviewAttachment(a.id) }, onOpen = { actions.onSaveAttachment(a.id) })
                }
            } }
            // Said aloud, or put into words by a live call's model, so not necessarily the user's own wording.
            if (item.voice) Row(Modifier.padding(end = Spacing.space4), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.space4)) {
                Icon(Lucide.Mic, Modifier.size(12.dp), tint = color(mutedColor))
                Text("By voice", style = TextStyles.caption, color = color(mutedColor))
            }
        }
        is ChatItem.VoiceCall -> Expandable(Lucide.AudioLines, "Voice call", subtitle = callLength(item.durationMillis)) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.space10)) {
                item.turns.forEach { turn ->
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.space2)) {
                        Text(if (turn.user) "You" else "Agent", style = TextStyles.caption, color = color(mutedColor))
                        Text(turn.text, style = TextStyles.secondary)
                    }
                }
            }
        }
        is ChatItem.Assistant -> Column(verticalArrangement = Arrangement.spacedBy(Spacing.space4)) {
            if (spoken != null) SpokenText(item.text, spoken, Modifier.fillMaxWidth()) else if (writing) StreamingMarkdown(item.text, Modifier.fillMaxWidth()) else Markdown(item.text, Modifier.fillMaxWidth())
            if (finished && item.text.isNotBlank()) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.space6)) {
                CopyButton(item.text)
                item.at?.let { Text(shortTime(it), style = TextStyles.caption, color = color(mutedColor)) }
            }
        }
        is ChatItem.Reasoning -> StepView(item)
        is ChatItem.ToolCall -> when {
            (item.identity?.instance?.pluginId == "kinuvo.computer" && item.identity?.localId in setOf("computer_capture", "computer_act")) && item.result == null -> Column(verticalArrangement = Arrangement.spacedBy(Spacing.space2)) {
                ToolCallView(item, actions = actions)
                Button(onClick = actions.onWatch, style = ButtonStyle.Ghost, contentColor = color(mutedColor)) { Text("Watch live", fontSize = TextStyles.caption.fontSize) }
            }
            item.name == "ask_user" && item.result != null && !item.isError -> Column { AnsweredQuestions(item); ToolAttachments(item.attachments, actions) }
            item.name == "generate_image" && item.result == null && !item.waiting -> GeneratingImage()
            item.name == "generate_image" && item.result != null && !item.isError -> Column(verticalArrangement = Arrangement.spacedBy(Spacing.space8)) {
                item.attachments.forEach { a ->
                    LaunchedEffect(a.id) { actions.onLoadAttachment(a.id) }
                    GeneratedImage(a, actions.downloads[a.id]?.base64, { actions.onPreviewAttachment(a.id) }, { actions.onSaveAttachment(a.id) })
                }
            }
            item.name == "show_html" && item.result != null && !item.isError -> HtmlSurface(item, actions.onSend) { ToolCallView(item, actions = actions) }
            item.app != null && item.result != null && !item.isError -> McpAppSurface(item, actions.onAppResource, actions.onAppTool, actions.onSend) { ToolCallView(item, actions = actions) }
            item.name == "show_ui" && item.result != null && !item.isError -> SurfaceCall(item, actions.onSurfaceAction) { ToolCallView(item, actions = actions) }
            item.name == "artifact" && item.result != null && !item.isError -> Column { ArtifactCard(item, actions.onOpenArtifact); ToolAttachments(item.attachments, actions) }
            else -> ToolCallView(item, actions = actions)
        }
        is ChatItem.Error -> Panel(
            Modifier.fillMaxWidth(),
            color = color(destructiveColor).copy(alpha = 0.08f),
            borderColor = color(destructiveColor).copy(alpha = 0.3f),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.space8)) {
                Icon(Lucide.CircleX, Modifier.size(16.dp).padding(top = Spacing.space2), tint = color(destructiveColor))
                Text(item.message, color = color(destructiveColor), style = TextStyles.secondary)
            }
        }
        is ChatItem.Notice -> Expandable(Lucide.TriangleAlert, item.message, tint = color(warningColor)) {
            // What it had written before stopping, quieter than a finished reply.
            Column(Modifier.alpha(0.85f), verticalArrangement = Arrangement.spacedBy(Spacing.space8)) { item.draft.forEach { ChatItemView(it, actions) } }
        }
        is ChatItem.Measurement -> Unit
        is ChatItem.Reflection -> ReflectionView(item, onReview = actions.onReviewReflection)
        is ChatItem.Compaction -> CompactionMarker(item)
        is ChatItem.ToolReport -> Expandable(Lucide.BookOpen, item.tool, subtitle = "background work") { Markdown(item.content) }
        is ChatItem.Activation -> Expandable(Lucide.BookOpen, item.name, subtitle = "skill") { Markdown(item.content) }
    }
}

@Composable
internal fun SurfaceActionLine(text: String) {
    Row(Modifier.fillMaxWidth().padding(start = Spacing.space48), horizontalArrangement = Arrangement.End) {
        Panel(color = color(selectedControlColor), borderColor = Color.Transparent, shape = PillShape,
            contentPadding = PaddingValues(horizontal = Spacing.space12, vertical = Spacing.space8)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.space6)) {
                Icon(Lucide.Send, Modifier.size(14.dp), contentDescription = "Your Surface action")
                Text("Submitted · $text", style = TextStyles.secondary)
            }
        }
    }
}

@Composable
private fun EmptyState(state: ChatState, modifier: Modifier) {
    Box(modifier.fillMaxWidth().padding(Spacing.space24), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.space10)) {
            val avatar = when {
                state.running -> AvatarState.Working
                state.voice?.active == true || state.dictation?.dictating == true -> AvatarState.Listening
                else -> AvatarState.Idle
            }
            RiveAvatar(BundledAvatar, Modifier.size(180.dp, 120.dp), LocalRiveGaze.current, state.agent?.avatarArtboard, state = avatar)
            // Balanced lines, so a wrap never leaves one word alone.
            Text(state.agent?.let { "Start a Session with ${it.name}" } ?: "Connecting…", style = TextStyles.headline.copy(lineBreak = LineBreak.Heading), lineHeight = 28.sp,
                fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            Text("Ask anything, or type /skill to activate one.", color = color(mutedColor), textAlign = TextAlign.Center, style = LocalTextStyle.current.copy(lineBreak = LineBreak.Heading))
        }
    }
}

/** Collapsible row used for tool calls, skills and reasoning. */
@Composable
internal fun Expandable(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    status: (@Composable () -> Unit)? = null,
    border: Boolean = true,
    initiallyExpanded: Boolean = false,
    tint: Color = Color.Unspecified,
    titleStyle: TextStyle = TextStyles.callout,
    subtitleStyle: TextStyle = TextStyles.caption,
    footer: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    val shape = Theme[shapes][mediumShape]
    Disclosure(expanded, { expanded = it }, Modifier.fillMaxWidth().then(if (border) Modifier.border(1.dp, color(borderColor), shape) else Modifier)) {
        Column {
            ListRow(selected = false, onClick = { expanded = !expanded }, modifier = Modifier.heightIn(min = 44.dp)) {
                Icon(icon, Modifier.size(15.dp), tint = if (tint.isSpecified) tint else color(mutedColor))
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.space8)) {
                    Text(title, style = titleStyle, fontWeight = FontWeight.Normal, singleLine = true)
                    subtitle?.let { Text(it, Modifier.weight(1f, fill = false), style = subtitleStyle, color = color(mutedColor), singleLine = true, overflow = TextOverflow.Ellipsis) }
                    status?.invoke()
                }
                Icon(if (expanded) Lucide.ChevronDown else Lucide.ChevronRight, Modifier.size(14.dp), tint = color(mutedColor))
            }
            footer?.let { Box(Modifier.padding(start = Spacing.space10, end = Spacing.space10, bottom = Spacing.space10)) { it() } }
            DisclosurePanel(contentPadding = PaddingValues(start = Spacing.space10, top = Spacing.space6, end = Spacing.space10, bottom = Spacing.space10)) { content() }
        }
    }
}

private class ToolLabel(val icon: ImageVector, val title: String, val subtitle: String?, val skill: Boolean = false)

private fun ChatItem.ToolCall.label(): ToolLabel {
    val identity = identity
    val local = identity?.localId ?: name
    val skill = if (identity?.instance?.pluginId == "kinuvo.core" && local == "activate_skill") Regex("\"name\"\\s*:\\s*\"([^\"]+)\"").find(args)?.groupValues?.get(1) else null
    return when {
        skill != null -> ToolLabel(Lucide.BookOpen, skill, "skill", skill = true)
        identity?.instance?.pluginId == "kinuvo.exec" && local == "shell" -> ToolLabel(Lucide.Terminal, local, commandOf(args)?.lineSequence()?.firstOrNull()?.take(80))
        identity != null -> ToolLabel(Lucide.Plug, local, identity.instance.let { "${it.pluginId} · ${it.instanceId}" })
        else -> ToolLabel(Lucide.Wrench, name, null)
    }
}

@Composable
private fun StepView(item: ChatItem, border: Boolean = true, actions: ChatActions = ChatActions()) {
    when (item) {
        is ChatItem.ToolCall -> ToolCallView(item, border, actions)
        is ChatItem.Reasoning -> Expandable(Lucide.Brain, "Thinking", border = border) {
            Text(item.text, style = LocalTextStyle.current.copy(fontStyle = FontStyle.Italic), color = color(mutedColor), fontSize = TextStyles.secondary.fontSize)
        }
        else -> Unit
    }
}

/**
 * Reasoning and tool calls on one line: the current step while [active], else a summary with any failures. Expands to
 * the steps, each expanding to its arguments and output. A single finished step shows as itself.
 */
@Composable
internal fun StepsView(row: ChatRow.Steps, active: Boolean, initiallyExpanded: Boolean = false, actions: ChatActions = ChatActions()) {
    row.steps.singleOrNull()?.takeIf { !active }?.let { return StepView(it, actions = actions) }
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
    val failed = row.failed
    val shape = Theme[shapes][mediumShape]
    val edge = if (failed > 0) color(destructiveColor).copy(alpha = 0.45f) else color(borderColor)
    Disclosure(expanded, { expanded = it }, Modifier.fillMaxWidth().border(1.dp, edge, shape)) {
        Column {
            ListRow(
                selected = false,
                onClick = { expanded = !expanded },
                modifier = Modifier.heightIn(min = 44.dp).semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" },
            ) {
                val current = row.steps.last()
                val running = (current as? ChatItem.ToolCall)?.takeIf { it.result == null }?.label()
                when {
                    active -> Spinner(size = 15.dp, tint = color(accentColor))
                    failed > 0 -> Icon(Lucide.CircleX, Modifier.size(15.dp), tint = color(destructiveColor))
                    else -> Icon(Lucide.ListChecks, Modifier.size(15.dp), tint = color(mutedColor))
                }
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.space8)) {
                    if (active && running != null) {
                        Text("Running", style = TextStyles.secondary, fontWeight = FontWeight.Normal, singleLine = true)
                        Text(running.title, style = if (running.skill) TextStyles.secondary else MonoStyle, singleLine = true, overflow = TextOverflow.Ellipsis)
                    } else Text(if (active && current is ChatItem.Reasoning) "Thinking…" else row.summary(),
                        style = TextStyles.secondary, fontWeight = FontWeight.Normal, singleLine = true, overflow = TextOverflow.Ellipsis)
                    if (active) running?.subtitle?.let { Text(it, style = TextStyles.caption, color = color(mutedColor), singleLine = true, overflow = TextOverflow.Ellipsis) }
                    else Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
                        // One icon per kind of tool, red when any of that kind failed.
                        val kinds = row.tools.groupBy { it.label().icon }.map { (icon, calls) -> icon to calls.any { it.isError } }
                        kinds.take(MaxStepIcons).forEach { (icon, failed) -> Icon(icon, Modifier.size(12.dp), tint = color(if (failed) destructiveColor else mutedColor)) }
                        if (kinds.size > MaxStepIcons) Text("+${kinds.size - MaxStepIcons}", style = TextStyles.overline, color = color(mutedColor))
                    }
                }
                if (failed > 0) Text("$failed failed", style = TextStyles.caption, color = color(destructiveColor), singleLine = true)
                else if (active && row.steps.size > 1) Text("${row.steps.size} steps", style = TextStyles.caption, color = color(mutedColor), singleLine = true)
                Icon(if (expanded) Lucide.ChevronDown else Lucide.ChevronRight, Modifier.size(14.dp), tint = color(mutedColor))
            }
            DisclosurePanel(contentPadding = PaddingValues(start = Spacing.space6, end = Spacing.space6, bottom = Spacing.space6)) {
                Column {
                    HorizontalSeparator(Modifier.padding(bottom = Spacing.space4))
                    row.steps.forEach { key(it.id) { StepView(it, border = false, actions = actions) } }
                }
            }
        }
    }
}

private const val MaxStepIcons = 6

/** E.g. "Thought, ran 3 tools" or "Ran shell". */
private fun ChatRow.Steps.summary(): String {
    val tools = tools
    val ran = when (tools.size) {
        0 -> null
        1 -> "ran ${tools[0].label().title}"
        else -> "ran ${tools.size} tools"
    }
    return listOfNotNull("thought".takeIf { steps.any { it is ChatItem.Reasoning } }, ran).joinToString(", ").replaceFirstChar { it.uppercase() }
}

@Composable
private fun ToolAttachments(attachments: List<Attachment>, actions: ChatActions) {
    attachments.forEach { a ->
        LaunchedEffect(a.id) { if (a.mimeType.startsWith("image/")) actions.onLoadAttachment(a.id) }
        AttachmentChip(a, actions.downloads[a.id]?.base64, onPreview = { actions.onPreviewAttachment(a.id) }, onOpen = { actions.onSaveAttachment(a.id) })
    }
}

@Composable
private fun ToolCallView(item: ChatItem.ToolCall, border: Boolean = true, actions: ChatActions = ChatActions()) {
    val label = item.label()
    Expandable(label.icon, label.title, label.subtitle, border = border, titleStyle = if (label.skill) TextStyles.callout else MonoStyle,
        subtitleStyle = if (label.skill) TextStyles.caption else MonoStyle.copy(fontSize = 12.sp), status = {
        when {
            item.waiting -> Pill("Needs you", tone = Tone.Warning)
            item.result == null -> Spinner(Modifier.semantics { contentDescription = "Running" }, size = 13.dp)
            item.denied -> Pill("Denied")
            item.isError -> Pill("Failed", tone = Tone.Danger)
            else -> Pill("Done", tone = Tone.Success)
        }
    }, footer = item.denialNote?.let { note -> { DenialNote(note) } }) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.space8)) {
            if (!label.skill && item.args.isNotBlank()) CodeBlock(prettyJson(item.args), language = "Arguments")
            item.result?.let { CodeBlock(it, language = if (item.isError) "Error" else "Result", previewChars = 4000, error = item.isError) }
            ToolAttachments(item.attachments, actions)
        }
    }
}

/** What the user told the Agent when denying a tool, as a quote under its row. */
@Composable
private fun DenialNote(note: String) = Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(Spacing.space8)) {
    Box(Modifier.width(2.dp).fillMaxHeight().background(color(borderColor)))
    Text("Your note: $note", style = TextStyles.secondary, color = color(mutedColor))
}

/** A past `ask_user` form: each question with what the user answered. */
@Composable
private fun AnsweredQuestions(item: ChatItem.ToolCall) {
    val args = runCatching { Json.parseToJsonElement(item.args).jsonObject }.getOrNull()
    val questions = runCatching { ProtocolJson.decodeFromJsonElement(ListSerializer(Question.serializer()), args!!.getValue("questions")) }.getOrDefault(emptyList())
    val answers = runCatching { Json.parseToJsonElement(item.result.orEmpty()).jsonObject }.getOrNull()
    Expandable(Lucide.MessageCircleQuestionMark, args?.get("title")?.jsonPrimitive?.content?.ifBlank { null } ?: "Questions", if (answers == null) "skipped" else "answered") {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.space8)) {
            questions.forEach { q ->
                val answer = answers?.get(q.id)?.let { a -> (a as? JsonArray)?.joinToString { it.jsonPrimitive.content } ?: a.jsonPrimitive.content }
                Column {
                    Text(q.label, style = TextStyles.caption, color = color(mutedColor))
                    Text(answer ?: "—", style = TextStyles.secondary)
                }
            }
        }
    }
}

/** A saved Artifact; its details come from the tool's JSON result. */
@Composable
private fun ArtifactCard(item: ChatItem.ToolCall, onOpen: (id: String) -> Unit) {
    val saved = runCatching { Json.parseToJsonElement(item.result.orEmpty()).jsonObject }.getOrNull()
    val id = saved?.get("id")?.jsonPrimitive?.content ?: return ToolCallView(item)
    Panel(Modifier.fillMaxWidth(), shape = Theme[shapes][largeShape], contentPadding = PaddingValues(horizontal = Spacing.space14, vertical = Spacing.space10)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.space12)) {
            Icon(Lucide.Shapes, Modifier.size(20.dp), tint = color(accentColor))
            Column(Modifier.weight(1f)) {
                Text(saved["title"]?.jsonPrimitive?.content ?: id, fontWeight = FontWeight.Normal, singleLine = true, overflow = TextOverflow.Ellipsis)
                Text(listOfNotNull(saved["type"]?.jsonPrimitive?.content, "artifact").joinToString(" · "), style = TextStyles.caption, color = color(mutedColor), singleLine = true)
            }
            Button(onClick = { onOpen(id) }, style = ButtonStyle.Outlined) { Text("Open") }
        }
    }
}

@Composable
internal fun RequestMetricsView(metrics: RequestMetrics, initiallyExpanded: Boolean = false, actions: ChatActions = ChatActions()) {
    val cached = metrics.cachedTokens?.let { "$it cached" } ?: "cache not reported"
    Expandable(Lucide.Gauge, "${metrics.kind} request", "$cached · ${metrics.latencyMs} ms", initiallyExpanded = initiallyExpanded) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.space4)) {
            Text("${metrics.provider} · ${metrics.model} · ${metrics.reasoning}", style = TextStyles.caption)
            Text("Duration: ${metrics.latencyMs} ms", style = TextStyles.caption)
            Text("Input: ${metrics.inputTokens ?: "not reported"} · Output: ${metrics.outputTokens ?: "not reported"}", style = TextStyles.caption)
            Text("Cache reads: ${metrics.cachedTokens ?: "not reported"} · Cache writes: ${metrics.cacheWriteTokens ?: "not reported"}", style = TextStyles.caption)
            metrics.firstTokenMs?.let { Text("First token: $it ms", style = TextStyles.caption) }
            if (!metrics.success) Text("Request did not finish", style = TextStyles.caption, color = color(destructiveColor))
        }
    }
}


@Composable
private fun CompactionMarker(item: ChatItem.Compaction) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            HorizontalSeparator(Modifier.weight(1f))
            Button(onClick = { expanded = !expanded }, style = ButtonStyle.Ghost, buttonSize = ButtonSize.Small, contentColor = color(mutedColor)) {
                Icon(Lucide.Archive, Modifier.size(13.dp))
                Spacer(Modifier.width(Spacing.space6))
                Text(if (expanded) "Earlier conversation compacted" else "Earlier conversation compacted · show summary", style = TextStyles.caption)
            }
            HorizontalSeparator(Modifier.weight(1f))
        }
        if (expanded) Markdown(item.summary, Modifier.padding(top = Spacing.space8))
    }
}

internal fun commandOf(args: String): String? =
    runCatching { Json.parseToJsonElement(args).jsonObject["command"]?.jsonPrimitive?.content }.getOrNull()

private val PrettyJson = Json { prettyPrint = true }

internal fun prettyJson(args: String): String =
    runCatching { PrettyJson.encodeToString(JsonElement.serializer(), Json.parseToJsonElement(args)) }.getOrDefault(args)

internal fun List<ChatItem>.attachment(id: String): Attachment? = firstNotNullOfOrNull { item ->
    when (item) {
        is ChatItem.User -> item.attachments.firstOrNull { it.id == id }
        is ChatItem.ToolCall -> item.attachments.firstOrNull { it.id == id }
        is ChatItem.Notice -> item.draft.attachment(id)
        else -> null
    }
}

private fun callLength(millis: Long) = (millis / 60_000).let { if (it < 1) "under a minute" else "$it min" }
