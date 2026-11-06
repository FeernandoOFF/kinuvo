package kinuvo.ui.surface

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import com.composables.ui.components.*
import com.composables.ui.theme.*
import dev.ynagai.a2ui.compose.*
import kinuvo.ui.design.*
import kotlinx.serialization.json.*

internal val surfaceComponents = ComponentRegistry(mapOf(
    "Icon" to ComponentRenderer(LayoutTraits.Fixed) { scope, modifier ->
        val name = scope.rememberString("name")
        Icon(catalogIcon(name), modifier.size(24.dp), contentDescription = name)
    },
    "Image" to ComponentRenderer(LayoutTraits.Content) { scope, modifier ->
        RemoteImage(scope.rememberString("url"), scope.rememberString("description"), modifier, scope.rememberString("fit"), scope.rememberString("variant"))
    },
    "Video" to ComponentRenderer(LayoutTraits.Content) { scope, modifier -> Media(scope, modifier, true) },
    "AudioPlayer" to ComponentRenderer(LayoutTraits.Content) { scope, modifier -> Media(scope, modifier, false) },
    "Tabs" to ComponentRenderer(LayoutTraits.Content) { scope, modifier ->
        val titles by remember(scope) { derivedStateOf {
            (scope.property("tabs") as? JsonArray).orEmpty().take(100).map { (it as? JsonObject)?.get("title")?.let(scope::dynamicString).orEmpty() }
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
        val min = scope.rememberNumber("min") ?: 0.0
        val max = scope.rememberNumber("max") ?: min
        val value = scope.rememberNumber("value") ?: min
        val divisions = scope.rememberNumber("steps")?.toInt()
        Column(modifier) {
            Slider(value, { next -> target?.let { scope.write(it, JsonPrimitive(next)) } }, min, max, divisions,
                scope.rememberString("label"), target != null)
            Failure(scope.rememberCheckFailures().firstMessage()?.message)
        }
    },
    "DateTimeInput" to ComponentRenderer(LayoutTraits.Content) { scope, modifier ->
        val target = scope.binding("value")
        val drafts = LocalSurfaceDrafts.current
        DisposableEffect(drafts, scope.component.id) { onDispose { drafts?.validate(scope.component.id, true) } }
        Column(modifier) {
            DateTimeInput(scope.rememberString("value").orEmpty(), { next -> target?.let { scope.write(it, JsonPrimitive(next)) } },
                scope.rememberBoolean("enableDate") ?: false, scope.rememberBoolean("enableTime") ?: false,
                scope.rememberString("min"), scope.rememberString("max"), scope.rememberString("label"), target != null,
                onValidityChange = { drafts?.validate(scope.component.id, it) })
            Failure(scope.rememberCheckFailures().firstMessage()?.message)
        }
    },
    "Text" to ComponentRenderer(LayoutTraits.Content) { scope, modifier ->
        val caption = scope.rememberString("variant") == "caption"
        Markdown(scope.rememberString("text").orEmpty(), modifier.width(IntrinsicSize.Max),
            style = if (caption) TextStyles.caption.copy(color = color(mutedColor)) else markdownTextStyle())
    },
    "Row" to ComponentRenderer(LayoutTraits.Content) { scope, modifier -> Layout(scope, modifier, true) },
    "Column" to ComponentRenderer(LayoutTraits.Content) { scope, modifier -> Layout(scope, modifier, false) },
    "List" to ComponentRenderer(LayoutTraits.Content) { scope, modifier ->
        val horizontal = scope.rememberString("direction") == "horizontal"
        Layout(scope, if (horizontal) modifier.horizontalScroll(rememberScrollState()) else modifier, horizontal)
    },
    "Card" to ComponentRenderer(LayoutTraits.Content) { scope, modifier ->
        Panel(modifier) { scope.rememberChildren("child").forEach { scope.RenderChild(it) } }
    },
    "Divider" to ComponentRenderer(LayoutTraits.Fixed) { scope, modifier ->
        if (scope.rememberString("axis") == "vertical") VerticalSeparator(modifier.heightIn(min = 24.dp))
        else HorizontalSeparator(modifier)
    },
    "Button" to ComponentRenderer(LayoutTraits.Content) { scope, modifier ->
        val action = scope.rememberAction("action")
        val style = when (scope.rememberString("variant")) {
            "primary" -> ButtonStyle.Primary
            "borderless" -> ButtonStyle.Ghost
            else -> ButtonStyle.Outlined
        }
        Button(onClick = { action?.let { scope.dispatch(it) } }, modifier = modifier, style = style,
            enabled = !scope.rememberCheckFailures().hasError()) {
            scope.rememberChildren("child").forEach { scope.RenderChild(it) }
        }
    },
    "TextField" to ComponentRenderer(LayoutTraits.Content) { scope, modifier ->
        val value = scope.rememberString("value").orEmpty()
        val state = rememberTextFieldState(value)
        val target = scope.binding("value")
        val variant = scope.rememberString("variant")
        val placeholder = scope.rememberString("placeholder").orEmpty()
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
            scope.rememberString("label")?.let { Text(it, style = TextStyles.secondary) }
            if (variant == "longText") TextArea(state, placeholder)
            else Input(state, placeholder, secret = variant == "obscured", error = failure != null)
            Failure(failure)
        }
    },
    "CheckBox" to ComponentRenderer(LayoutTraits.Content) { scope, modifier ->
        val checked = scope.rememberBoolean("value") ?: false
        val target = scope.binding("value")
        Column(modifier) {
            Checkbox(checked, { target?.let { path -> scope.write(path, JsonPrimitive(it)) } }, enabled = target != null) {
                Text(scope.rememberString("label").orEmpty())
            }
            Failure(scope.rememberCheckFailures().firstMessage()?.message)
        }
    },
    "ChoicePicker" to ComponentRenderer(LayoutTraits.Content) { scope, modifier ->
        val selected = scope.rememberStringList("value").orEmpty()
        val target = scope.binding("value")
        val exclusive = scope.rememberString("variant") != "multipleSelection"
        val options by remember(scope) { derivedStateOf {
            (scope.property("options") as? JsonArray).orEmpty().mapNotNull { raw ->
                val option = raw as? JsonObject ?: return@mapNotNull null
                val value = (option["value"] as? JsonPrimitive)?.content ?: return@mapNotNull null
                val label = option["label"]?.let(scope::dynamicString) ?: return@mapNotNull null
                value to label
            }
        } }
        val filter = rememberTextFieldState()
        val filterable = scope.rememberBoolean("filterable") == true
        val chips = scope.rememberString("displayStyle") == "chips"
        val visible = options.filter { !filterable || it.second.contains(filter.text, ignoreCase = true) }
        val change: (List<String>) -> Unit = { values -> target?.let { scope.write(it, JsonArray(values.map(::JsonPrimitive))) } }
        Column(modifier, verticalArrangement = Arrangement.spacedBy(Spacing.space6)) {
            scope.rememberString("label")?.let { Text(it, style = TextStyles.secondary) }
            if (filterable) Input(filter, "Filter options")
            if (chips) FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.space6)) {
                visible.forEach { (value, label) ->
                    Button(onClick = { change(if (exclusive) listOf(value) else if (value in selected) selected - value else selected + value) },
                        modifier = Modifier.semantics { this.selected = value in selected }, enabled = target != null, style = if (value in selected) ButtonStyle.Primary else ButtonStyle.Outlined) { Text(label) }
                }
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

private val FILL_COMPONENTS = setOf("TextField", "DateTimeInput", "Slider")

@Composable
private fun Layout(scope: A2uiComponentScope, modifier: Modifier, horizontal: Boolean) {
    val justify = scope.rememberString("justify")
    val align = scope.rememberString("align")
    val children = scope.rememberAllChildren()
    fun weight(child: A2uiChild) = scope.surface?.components?.get(child.componentId)?.let { component ->
        (component.properties["weight"] as? JsonPrimitive)?.floatOrNull?.takeIf { it.isFinite() && it > 0 }
            ?: 1f.takeIf { horizontal && component.component in FILL_COMPONENTS }
    }
    val layout = if (justify != null && justify != "start") {
        if (horizontal) modifier.fillMaxWidth() else modifier.fillMaxHeight()
    } else modifier
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
        else -> Arrangement.spacedBy(Spacing.space12)
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
    val url = scope.rememberString("url").orEmpty()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Spacing.space6)) {
        if (video) scope.rememberString("posterUrl")?.let { RemoteImage(it, "Video preview", variant = "header") }
        MediaCard(if (video) "Video" else "Audio", scope.rememberString("description"), {
            if (url.startsWith("https://") || url.startsWith("http://")) runCatching { opener.openUri(url) }
        })
    }
}
