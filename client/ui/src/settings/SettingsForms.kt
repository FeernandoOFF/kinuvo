package kinuvo.ui.settings

import kinuvo.app.connection.*
import kinuvo.app.session.*
import kinuvo.app.navigation.*
import kinuvo.ui.connection.*
import kinuvo.ui.device.*
import kinuvo.ui.session.*
import kinuvo.ui.board.*
import kinuvo.ui.routine.*
import kinuvo.ui.navigation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.*
import com.composables.ui.components.*
import com.composables.ui.theme.*
import kinuvo.protocol.agent.*
import kinuvo.ui.design.*
/** A section's list: title with an add button, an intro, then its [rows], or a spinner while [loading]. */
@Composable
internal fun ItemList(p: Pane, title: String, intro: String, addLabel: String, loading: Boolean, canAdd: Boolean = true, rows: @Composable ColumnScope.() -> Unit) {
    val add = PageAction(Lucide.Plus, addLabel) { p.go(p.ui.route.copy(item = null, editing = true)) }
    val scope = LocalAgentScope.current
    Column(Modifier.fillMaxSize()) {
        if (scope != null && p.back == null) PageHeader(p.ui.route.section?.icon() ?: Lucide.Settings, title, scope = scope, actions = if (canAdd) listOf(add) else emptyList())
        else SettingsPageHeader(title, p.back) { if (canAdd) HeaderButton(add.icon, add.label, add.onClick) }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Spacing.space16, vertical = Spacing.space12), verticalArrangement = Arrangement.spacedBy(Spacing.space16)) {
            if (intro.isNotEmpty()) Text(intro, color = color(mutedColor), style = TextStyles.secondary.copy(lineBreak = LineBreak.Heading))
            if (loading) Box(Modifier.fillMaxWidth().padding(Spacing.space24), contentAlignment = Alignment.Center) { Spinner() }
            else rows()
        }
    }
}

/** A list row with a title, a muted [subtitle] and trailing pills. */
@Composable
internal fun ItemRow(selected: Boolean, icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit, muted: Boolean = false, badges: @Composable RowScope.() -> Unit = {}) =
    GroupRow(title, onClick, subtitle, icon, selected = selected, titleLines = 2, tint = if (muted) color(mutedColor) else Color.Unspecified, badges = badges)

/**
 * A detail or form screen: a title bar with [trailing] controls over a centered, scrolling column, led by any [error].
 * [info] describes the screen behind a (?) by the title.
 */
@Composable
internal fun DetailPane(back: (() -> Unit)?, title: String, error: String? = null, info: String? = null, subtitle: String? = null, trailing: @Composable RowScope.() -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize()) {
        SettingsPageHeader(title, back, subtitle = subtitle, info = info, trailing = trailing)
        Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 680.dp).fillMaxWidth().padding(horizontal = Spacing.space16, vertical = Spacing.space12),
                verticalArrangement = Arrangement.spacedBy(Spacing.space16),
            ) {
                error?.let { ErrorMessage(it) }
                content()
            }
        }
    }
}

@Composable
internal fun DetailGroup(title: String, content: @Composable ColumnScope.() -> Unit) = RowGroup(title) { GroupBox(content) }

/** A label beside its value; narrow, above it. */
@Composable
internal fun DetailRow(label: String, value: @Composable () -> Unit) {
    BoxWithConstraints {
        if (maxWidth < 420.dp) Column(verticalArrangement = Arrangement.spacedBy(Spacing.space2)) {
            if (label.isNotEmpty()) Text(label, color = color(mutedColor), style = TextStyles.secondary)
            value()
        } else Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.width(120.dp), color = color(mutedColor), style = TextStyles.secondary)
            Box(Modifier.weight(1f)) { value() }
        }
    }
}

/** Save and Cancel for a form; [error] is the last failure. */
@Composable
internal fun FormButtons(saveLabel: String, enabled: Boolean, error: String?, onSave: () -> Unit, onCancel: (() -> Unit)?) {
    error?.let { ErrorMessage(it) }
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.space8)) {
        Button(onClick = onSave, enabled = enabled, style = ButtonStyle.Primary, buttonSize = ButtonSize.Small) { Text(saveLabel) }
        onCancel?.let { Button(onClick = it, style = ButtonStyle.Ghost, buttonSize = ButtonSize.Small) { Text("Cancel") } }
    }
}

/** A small Save button, outlined while there's nothing to save. */
@Composable
internal fun SaveButton(onSave: () -> Unit, enabled: Boolean = true) =
    Button(onClick = onSave, enabled = enabled, style = if (enabled) ButtonStyle.Primary else ButtonStyle.Outlined, buttonSize = ButtonSize.Small) { Text("Save") }

/** Save, and Cancel where [onCancel] is set, for a form's header; a touch screen's sheet shows Save while there's something to save. */
@Composable
internal fun FormActions(saveLabel: String, enabled: Boolean, saved: Boolean, onSave: () -> Unit, onCancel: (() -> Unit)? = null, dirty: Boolean = enabled) {
    TrackSettingsEdits(dirty)
    if (LocalInteractionMode.current == InteractionMode.Touch) {
        if (dirty) SaveButton(onSave, enabled)
        return
    }
    Row(Modifier.titleBarControl(), horizontalArrangement = Arrangement.spacedBy(Spacing.space6), verticalAlignment = Alignment.CenterVertically) {
        if (saved) Pill("Saved", tone = Tone.Success, icon = Lucide.CircleCheck)
        onCancel?.let { Button(onClick = it, style = ButtonStyle.Ghost, buttonSize = ButtonSize.Small) { Text("Cancel") } }
        Button(onClick = onSave, enabled = enabled, style = ButtonStyle.Primary, buttonSize = ButtonSize.Small) { Text(saveLabel) }
    }
}

/** Delete that asks once more before calling [onDelete]. */
@Composable
internal fun DeleteButton(what: String, key: Any, onDelete: () -> Unit) {
    var confirm by remember(key) { mutableStateOf(false) }
    ActionButton(Lucide.Trash2, if (confirm) "Delete this $what?" else "Delete", { if (confirm) onDelete() else confirm = true }, destructive = true)
}

internal fun ConfigScope.label() = when (this) {
    ConfigScope.Agent -> "This agent"
    ConfigScope.Shared -> "Shared"
    ConfigScope.External -> "External"
}

internal fun TextFieldState.value() = text.toString().trim()

/** Non-blank lines, e.g. one argument or `KEY=value` per line. */
internal fun TextFieldState.lines() = text.lines().map { it.trim() }.filter { it.isNotEmpty() }

internal fun TextFieldState.commaList() = text.split(",").map { it.trim() }.filter { it.isNotEmpty() }
