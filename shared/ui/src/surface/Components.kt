package kinuvo.ui.surface

import com.composables.icons.lucide.*
import androidx.compose.animation.Crossfade
import dev.ynagai.a2ui.core.protocol.Action
import kinuvo.app.surface.stepperValue
import kinuvo.app.surface.formatSurfaceDuration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import com.composeunstyled.LocalTextStyle
import kinuvo.app.surface.SurfaceFunctions
import kinuvo.app.surface.surfaceMarkdownTable
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import com.composables.ui.components.*
import com.composables.ui.theme.*
import dev.ynagai.a2ui.compose.*
import kinuvo.ui.design.*
import kotlinx.serialization.json.*

private val LocalSurfaceButton = compositionLocalOf { false }
private val LocalTableWidths = compositionLocalOf<List<Dp>> { emptyList() }

internal val surfaceComponents = ComponentRegistry(mapOf(
    "Show" to ComponentRenderer(LayoutTraits.Content) { scope, modifier ->
        if (((scope.surfaceBoolean("when") ?: ((scope.surfaceNumber("when") ?: 0.0) != 0.0))) != (scope.surfaceBoolean("empty") == true)) scope.rememberChildren("child").forEach { scope.RenderChild(it, modifier) }
    },
    "Progress" to ComponentRenderer(LayoutTraits.Content) { scope, modifier ->
        val max = scope.surfaceNumber("max")?.takeIf { it.isFinite() && it > 0 } ?: 1.0
        val value = ((scope.surfaceNumber("value") ?: 0.0) / max).takeIf { it.isFinite() }?.coerceIn(0.0, 1.0) ?: 0.0
        Box(modifier.widthIn(min = 48.dp).fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(color(mutedColor).copy(alpha = 0.22f))) {
            Box(Modifier.fillMaxWidth(value.toFloat()).fillMaxHeight().background(color(accentColor)))
        }
    },
    "Stepper" to ComponentRenderer(LayoutTraits.Content) { scope, modifier ->
        val target = scope.binding("value")
        val value = scope.surfaceNumber("value") ?: 0.0
        val step = (scope.surfaceNumber("step") ?: 15.0).coerceAtLeast(0.0)
        val min = scope.surfaceNumber("min") ?: 0.0
        val max = (scope.surfaceNumber("max") ?: Double.MAX_VALUE).coerceAtLeast(min)
        val compact = LocalTableWidths.current.isNotEmpty()
        val buttonSize = if (compact) 24.dp else 32.dp
        Row(modifier, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { target?.let { scope.write(it, JsonPrimitive(stepperValue(value, -step, min, max))) } }, modifier = Modifier.size(buttonSize), enabled = target != null && value > min, style = ButtonStyle.Ghost) { Icon(Lucide.Minus, Modifier.size(16.dp), contentDescription = "Decrease") }
            Text(if (scope.surfaceBoolean("duration") == true) formatSurfaceDuration(value) else if (value % 1.0 == 0.0) value.toLong().toString() else value.toString(), Modifier.padding(horizontal = 4.dp), style = if (compact) TextStyles.caption else markdownTextStyle())
            IconButton(onClick = { target?.let { scope.write(it, JsonPrimitive(stepperValue(value, step, min, max))) } }, modifier = Modifier.size(buttonSize), enabled = target != null && value < max, style = ButtonStyle.Ghost) { Icon(Lucide.Plus, Modifier.size(16.dp), contentDescription = "Increase") }
        }
    },
    "Badge" to ComponentRenderer(LayoutTraits.Content) { scope, modifier ->
        val tone = when (scope.surfaceString("color")) {
            "success" -> Tone.Success
            "danger" -> Tone.Danger
            "warning" -> Tone.Warning
            "info", "discovery" -> Tone.Accent
            else -> Tone.Neutral
        }
        SurfaceBadge(scope.surfaceString("label").orEmpty(), tone, scope.surfaceString("variant"), scope.surfaceString("size"), scope.surfaceBoolean("pill") == true, modifier)
    },
    "Spacer" to ComponentRenderer(LayoutTraits.Fixed) { scope, modifier -> Spacer(modifier.size((scope.surfaceNumber("minSize") ?: 8.0).coerceIn(0.0, 1000.0).dp)) },
    "ListItem" to ComponentRenderer(LayoutTraits.Content) { scope, modifier ->
        val action = scope.rememberAction("action")
        Row(modifier.then(if (action != null) Modifier.clickable { scope.dispatchLocal(action) } else Modifier).padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            scope.rememberChildren("leading").forEach { scope.RenderChild(it) }
            Column(Modifier.weight(1f)) {
                Text(scope.surfaceString("title").orEmpty())
                scope.surfaceString("subtitle")?.let { Text(it, style = TextStyles.caption) }
            }
            scope.rememberChildren("trailing").forEach { scope.RenderChild(it) }
        }
    },
    "Table" to ComponentRenderer(LayoutTraits.Content) { scope, modifier ->
        val measurer = rememberTextMeasurer()
        val density = LocalDensity.current
        val style = markdownTextStyle()
        val components = scope.surface?.components.orEmpty()
        fun labels(id: String, depth: Int = 0): List<String> {
            if (depth > 16) return emptyList()
            val component = components[id] ?: return emptyList()
            val own = listOfNotNull((component.properties["text"] as? JsonPrimitive)?.content,
                (component.properties["placeholder"] as? JsonPrimitive)?.content) +
                (component.properties["options"] as? JsonArray).orEmpty().mapNotNull { ((it as? JsonObject)?.get("label") as? JsonPrimitive)?.content }
            val children = (component.properties["children"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content } +
                listOfNotNull((component.properties["child"] as? JsonPrimitive)?.content)
            return own + children.flatMap { labels(it, depth + 1) }
        }
        fun rowCells(id: String, visited: Set<String> = emptySet()): List<List<String>> {
            if (id in visited) return emptyList()
            val component = components[id] ?: return emptyList()
            val children = component.properties["children"]
            val ids = (children as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content } +
                listOfNotNull(((children as? JsonObject)?.get("componentId") as? JsonPrimitive)?.content,
                    (component.properties["child"] as? JsonPrimitive)?.content)
            return if (component.component == "TableRow") listOf(ids) else ids.flatMap { rowCells(it, visited + id) }
        }
        val rows = rowCells(scope.component.id)
        val measured = (0 until (rows.maxOfOrNull { it.size } ?: 0)).map { column ->
            val pixels = rows.flatMap { it.getOrNull(column)?.let { id -> labels(id) }.orEmpty() }
                .maxOfOrNull { measurer.measure(it, style, softWrap = false).size.width } ?: 0
            with(density) { pixels.toDp() }.plus(24.dp).coerceIn(40.dp, 240.dp)
        }
        TableFrame(measured, modifier, compact = true) { widths ->
            CompositionLocalProvider(LocalTableWidths provides widths) {
                scope.rememberChildren("children").forEach { scope.RenderChild(it) }
            }
        }
    },
    "TableRow" to ComponentRenderer(LayoutTraits.Content) { scope, modifier ->
        val header = scope.surfaceBoolean("header") == true
        val children = scope.rememberChildren("children")
        CompositionLocalProvider(LocalTextStyle provides LocalTextStyle.current.let { if (header) it.copy(fontWeight = FontWeight.Bold) else it }) {
            Column(modifier.then(if (header) Modifier.semantics { heading() } else Modifier)) {
                TableLine(LocalTableWidths.current.ifEmpty { List(children.size) { 160.dp } }, true, compact = true) { column ->
                    children.getOrNull(column)?.let { scope.RenderChild(it) }
                }
            }
        }
    },
    "TableCell" to ComponentRenderer(LayoutTraits.Content) { scope, modifier ->
        Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = when (scope.surfaceString("align")) {
            "center" -> Alignment.CenterHorizontally
            "end" -> Alignment.End
            else -> Alignment.Start
        }) { scope.rememberChildren("children").forEach { scope.RenderChild(it) } }
    },
    "Transition" to ComponentRenderer(LayoutTraits.Content) { scope, modifier ->
        val children = scope.rememberChildren("child")
        val target = children to children.map { scope.surface?.components?.get(it.componentId) }
        Crossfade(target, modifier) { (items, _) -> items.forEach { scope.RenderChild(it) } }
    },
    "Icon" to ComponentRenderer(LayoutTraits.Fixed) { scope, modifier ->
        val name = scope.surfaceString("name")
        Icon(catalogIcon(name), modifier.size(if (LocalSurfaceButton.current) 18.dp else 24.dp), contentDescription = name)
    },
    "Image" to ComponentRenderer(LayoutTraits.Content) { scope, modifier ->
        RemoteImage(scope.surfaceString("url"), scope.surfaceString("description"), modifier, scope.surfaceString("fit"), scope.surfaceString("variant"))
    },
    "Video" to ComponentRenderer(LayoutTraits.Content) { scope, modifier -> Media(scope, modifier, true) },
    "AudioPlayer" to ComponentRenderer(LayoutTraits.Content) { scope, modifier -> Media(scope, modifier, false) },
    "Tabs" to ComponentRenderer(LayoutTraits.Content) { scope, modifier ->
        val titles by remember(scope) { derivedStateOf {
            (scope.property("tabs") as? JsonArray).orEmpty().take(100).map { (it as? JsonObject)?.get("title")?.let(scope::surfaceText).orEmpty() }
        } }
        var selected by remember(scope) { mutableIntStateOf(0) }
        if (titles.isNotEmpty()) Tabs(titles, selected.coerceAtMost(titles.lastIndex), { selected = it }, modifier) {
            scope.rememberChildren("tabs/$selected/child").forEach { scope.RenderChild(it) }
        }
    },
    "Modal" to ComponentRenderer(LayoutTraits.Content) { scope, modifier ->
        Modal(modifier, trigger = { scope.rememberChildren("trigger").forEach { scope.RenderChild(it) } }) {
            scope.rememberChildren("content").forEach { scope.RenderChild(it) }
        }
    },
    "Slider" to ComponentRenderer(LayoutTraits.Content) { scope, modifier ->
        val target = scope.binding("value")
        val min = scope.surfaceNumber("min") ?: 0.0
        val max = scope.surfaceNumber("max") ?: min
        val value = scope.surfaceNumber("value") ?: min
        val divisions = scope.surfaceNumber("steps")?.toInt()
        Column(modifier) {
            Slider(value, { next -> target?.let { scope.write(it, JsonPrimitive(next)) } }, min, max, divisions,
                scope.surfaceString("label"), target != null)
            Failure(scope.rememberCheckFailures().firstMessage()?.message)
        }
    },
    "DateTimeInput" to ComponentRenderer(LayoutTraits.Content) { scope, modifier ->
        val target = scope.binding("value")
        val drafts = LocalSurfaceDrafts.current
        val input = remember { Any() }
        DisposableEffect(drafts) { onDispose { drafts?.validate(input, true) } }
        Column(modifier) {
            DateTimeInput(scope.surfaceString("value").orEmpty(), { next -> target?.let { scope.write(it, JsonPrimitive(next)) } },
                scope.surfaceBoolean("enableDate") ?: false, scope.surfaceBoolean("enableTime") ?: false,
                scope.surfaceString("min"), scope.surfaceString("max"), scope.surfaceString("label"), target != null,
                onValidityChange = { drafts?.validate(input, it) })
            Failure(scope.rememberCheckFailures().firstMessage()?.message)
        }
    },
    "Text" to ComponentRenderer(LayoutTraits.Content) { scope, modifier ->
        val variant = scope.surfaceString("variant")
        val caption = variant == "caption"
        if (LocalSurfaceButton.current) Text(scope.surfaceString("text").orEmpty(), modifier, style = markdownTextStyle())
        else Markdown(scope.surfaceString("text").orEmpty(), modifier,
            style = if (caption) TextStyles.caption.copy(color = color(mutedColor)) else when (variant) {
                "h1" -> TextStyles.headline
                "h2", "h3", "h4", "h5" -> TextStyles.title
                else -> markdownTextStyle().let { if (LocalTextStyle.current.fontWeight == FontWeight.Bold) it.copy(fontWeight = FontWeight.Bold) else it }
            })
    },
    "Row" to ComponentRenderer(LayoutTraits.Content) { scope, modifier -> Layout(scope, modifier, true) },
    "Column" to ComponentRenderer(LayoutTraits.Content) { scope, modifier -> Layout(scope, modifier, false) },
    "List" to ComponentRenderer(LayoutTraits.Content) { scope, modifier ->
        val horizontal = scope.surfaceString("direction") == "horizontal"
        Layout(scope, if (horizontal) modifier.horizontalScroll(rememberScrollState()) else modifier, horizontal)
    },
    "Card" to ComponentRenderer(LayoutTraits.Content) { scope, modifier ->
        Panel(modifier.fillMaxWidth()) { scope.rememberChildren("child").forEach { scope.RenderChild(it, Modifier.fillMaxWidth()) } }
    },
    "Divider" to ComponentRenderer(LayoutTraits.Fixed) { scope, modifier ->
        if (scope.surfaceString("axis") == "vertical") VerticalSeparator(modifier.heightIn(min = 24.dp))
        else HorizontalSeparator(modifier)
    },
    "Button" to ComponentRenderer(LayoutTraits.Content) { scope, modifier ->
        val action = scope.rememberAction("action")
        val clipboard = LocalClipboardManager.current
        val style = when (scope.surfaceString("variant")) {
            "primary" -> ButtonStyle.Primary
            "borderless" -> ButtonStyle.Ghost
            else -> ButtonStyle.Outlined
        }
        val children = scope.rememberChildren("child")
        val iconOnly = children.singleOrNull()?.let { scope.surface?.components?.get(it.componentId)?.component == "Icon" } == true
        val onClick: () -> Unit = {
            if (action is Action.Invoke && action.functionCall.call == "copy") {
                val args = action.functionCall.args.orEmpty()
                val functions = scope.surface?.let { SurfaceFunctions(it.dataModel, scope.evaluationScope) }
                val text = args["text"]?.let { functions?.evaluate(it) }?.let { (it as? JsonPrimitive)?.content }
                    ?: (args["path"] as? JsonPrimitive)?.content?.let { path -> functions?.evaluate(buildJsonObject { put("path", path) }) }?.let(::surfaceMarkdownTable)
                text?.let { clipboard.setText(AnnotatedString(it)) }
            } else action?.let { scope.dispatchLocal(it) }
        }
        val enabled = !scope.rememberCheckFailures().hasError()
        val content: @Composable () -> Unit = {
            CompositionLocalProvider(LocalSurfaceButton provides true) { children.forEach { scope.RenderChild(it) } }
        }
        if (iconOnly) IconButton(onClick = onClick, modifier = modifier, style = style, enabled = enabled, content = content)
        else Button(onClick = onClick, modifier = modifier, style = style, enabled = enabled) { content() }
    },
    "TextField" to ComponentRenderer(LayoutTraits.Content) { scope, modifier ->
        val value = scope.surfaceString("value").orEmpty()
        val state = rememberTextFieldState(value)
        val target = scope.binding("value")
        val variant = scope.surfaceString("variant")
        val placeholder = scope.surfaceString("placeholder").orEmpty()
        val failure = scope.rememberCheckFailures().firstMessage()?.message
        LaunchedEffect(value) {
            val current = state.text.toString()
            val sameNumber = variant == "number" && current.toDoubleOrNull()?.let { it == value.toDoubleOrNull() } == true
            if (current != value && !sameNumber) state.edit { replace(0, length, value) }
        }
        LaunchedEffect(state, scope, target, variant) {
            snapshotFlow { state.text.toString() }.collect { text ->
                target?.let { scope.write(it, if (variant == "number") text.toDoubleOrNull()?.takeIf { it.isFinite() }?.let(::JsonPrimitive) ?: JsonPrimitive(text) else JsonPrimitive(text)) }
            }
        }
        Column(modifier, verticalArrangement = Arrangement.spacedBy(Spacing.space6)) {
            scope.surfaceString("label")?.let { Text(it, style = TextStyles.secondary) }
            if (variant == "longText") TextArea(state, placeholder)
            else Input(state, placeholder, modifier = if (LocalTableWidths.current.isNotEmpty()) Modifier.height(36.dp) else Modifier, secret = variant == "obscured", error = failure != null, compact = LocalTableWidths.current.isNotEmpty())
            Failure(failure)
        }
    },
    "CheckBox" to ComponentRenderer(LayoutTraits.Content) { scope, modifier ->
        val checked = scope.surfaceBoolean("value") ?: false
        val target = scope.binding("value")
        Column(modifier) {
            Checkbox(checked, { target?.let { path -> scope.write(path, JsonPrimitive(it)) } }, enabled = target != null) {
                Text(scope.surfaceString("label").orEmpty())
            }
            Failure(scope.rememberCheckFailures().firstMessage()?.message)
        }
    },
    "ChoicePicker" to ComponentRenderer(LayoutTraits.Content) { scope, modifier ->
        val selected = scope.rememberStringList("value").orEmpty()
        val target = scope.binding("value")
        val exclusive = scope.surfaceString("variant") != "multipleSelection"
        val options by remember(scope) { derivedStateOf {
            (scope.property("options") as? JsonArray).orEmpty().mapNotNull { raw ->
                val option = raw as? JsonObject ?: return@mapNotNull null
                val value = (option["value"] as? JsonPrimitive)?.content ?: return@mapNotNull null
                val label = option["label"]?.let(scope::surfaceText) ?: return@mapNotNull null
                value to label
            }
        } }
        val filter = rememberTextFieldState()
        val filterable = scope.surfaceBoolean("filterable") == true
        val segmented = exclusive && scope.surfaceString("displayStyle") == "segmented"
        val chips = scope.surfaceString("displayStyle") == "chips"
        val visible = options.filter { !filterable || it.second.contains(filter.text, ignoreCase = true) }
        val change: (List<String>) -> Unit = { values -> target?.let { scope.write(it, JsonArray(values.map(::JsonPrimitive))) } }
        Column(modifier, verticalArrangement = Arrangement.spacedBy(Spacing.space6)) {
            scope.surfaceString("label")?.let { Text(it, style = TextStyles.secondary) }
            if (filterable) Input(filter, "Filter options")
            if (segmented) Row(Modifier.clip(RoundedCornerShape(10.dp)).background(color(subtleColor)).padding(4.dp)) {
                visible.forEach { (value, label) ->
                    Button(onClick = { change(listOf(value)) }, enabled = target != null,
                        modifier = Modifier.semantics { this.selected = value in selected },
                        style = if (value in selected) ButtonStyle.Primary else ButtonStyle.Ghost) { Text(label) }
                }
            } else if (chips) FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.space6)) {
                visible.forEach { (value, label) ->
                    Button(onClick = { change(if (exclusive) listOf(value) else if (value in selected) selected - value else selected + value) },
                        modifier = Modifier.semantics { this.selected = value in selected }, enabled = target != null, style = if (value in selected) ButtonStyle.Primary else ButtonStyle.Outlined) { Text(label) }
                }
            } else if (exclusive && scope.surfaceString("displayStyle") == "dropdown") {
                ChoiceSelect(visible, selected.firstOrNull(), target != null, compact = LocalTableWidths.current.isNotEmpty()) { change(listOf(it)) }
            } else if (exclusive) RadioGroup(selected.firstOrNull(), { change(listOf(it)) }) {
                Column { visible.forEach { (value, label) -> Radio(value, enabled = target != null) { Text(label) } } }
            } else visible.forEach { (value, label) ->
                Checkbox(value in selected, { change(if (it) selected + value else selected - value) }, enabled = target != null) { Text(label) }
            }
            Failure(scope.rememberCheckFailures().firstMessage()?.message)
        }
    },
))

@Composable
private fun Failure(message: String?) {
    if (message != null) Text(message, color = color(destructiveColor), style = TextStyles.caption)
}

private val FILL_COMPONENTS = setOf("TextField", "DateTimeInput", "Slider", "Spacer")

@Composable
private fun Layout(scope: A2uiComponentScope, modifier: Modifier, horizontal: Boolean) {
    val justify = scope.surfaceString("justify")
    val align = scope.surfaceString("align") ?: if (horizontal) "center" else "stretch"
    val children = scope.rememberAllChildren()
    fun weight(child: A2uiChild) = scope.surface?.components?.get(child.componentId)?.let { component ->
        (component.properties["weight"] as? JsonPrimitive)?.floatOrNull?.takeIf { it.isFinite() && it > 0 }
            ?: 1f.takeIf { horizontal && component.component in FILL_COMPONENTS }
    }
    val layout = if (justify != null && justify != "start") {
        if (horizontal) modifier.fillMaxWidth() else modifier.fillMaxHeight()
    } else modifier.fillMaxWidth()
    if (horizontal) Row(if (align == "stretch") layout.height(IntrinsicSize.Min) else layout, horizontalArrangement = when (justify) {
        "center" -> Arrangement.Center
        "end" -> Arrangement.End
        "spaceBetween" -> Arrangement.SpaceBetween
        "spaceAround" -> Arrangement.SpaceAround
        "spaceEvenly" -> Arrangement.SpaceEvenly
        else -> Arrangement.spacedBy(Spacing.space8)
    }, verticalAlignment = when (align) {
        "center" -> Alignment.CenterVertically
        "end" -> Alignment.Bottom
        else -> Alignment.Top
    }) {
        children.forEach { child -> scope.RenderChild(child, (weight(child)?.let { Modifier.weight(it) } ?: Modifier)
            .then(if (align == "stretch") Modifier.fillMaxHeight() else Modifier)) }
    } else Column(layout, verticalArrangement = when (justify) {
        "center" -> Arrangement.Center
        "end" -> Arrangement.Bottom
        "spaceBetween" -> Arrangement.SpaceBetween
        "spaceAround" -> Arrangement.SpaceAround
        "spaceEvenly" -> Arrangement.SpaceEvenly
        else -> Arrangement.spacedBy(if (LocalTableWidths.current.isNotEmpty() && children.all {
            scope.surface?.components?.get(it.componentId)?.component == "TableRow"
        }) 0.dp else Spacing.space12)
    }, horizontalAlignment = when (align) {
        "center" -> Alignment.CenterHorizontally
        "end" -> Alignment.End
        else -> Alignment.Start
    }) {
        children.forEach { child -> scope.RenderChild(child, (weight(child)?.let { Modifier.weight(it) } ?: Modifier)
            .then(if (align == "stretch") Modifier.fillMaxWidth() else Modifier)) }
    }
}

@Composable
private fun Media(scope: A2uiComponentScope, modifier: Modifier, video: Boolean) {
    val opener = LocalUriHandler.current
    val url = scope.surfaceString("url").orEmpty()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Spacing.space6)) {
        if (video) scope.surfaceString("posterUrl")?.let { RemoteImage(it, "Video preview", variant = "header") }
        MediaCard(if (video) "Video" else "Audio", scope.surfaceString("description"), {
            if (url.startsWith("https://") || url.startsWith("http://")) runCatching { opener.openUri(url) }
        })
    }
}
