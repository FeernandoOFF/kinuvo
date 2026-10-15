package kinuvo.ui.agent

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.*
import com.composables.ui.components.*
import kinuvo.app.agent.ServiceFormState
import kinuvo.protocol.*
import kinuvo.ui.connection.ServerLoading
import kinuvo.ui.design.*
import kinuvo.ui.session.HeaderButton

private fun ServiceKind.title() = if (this == ServiceKind.Search) "Web search" else "Notifications"

@Composable
internal fun ServiceSettingsPane(p: Pane, kind: ServiceKind) {
    val setup = p.ui.state.services[kind] ?: return ServerLoading(p, kind.title())
    key(setup) { ServiceForm(setup, p) }
}

@Composable
private fun ServiceForm(setup: ServiceSetup, p: Pane) {
    var form by remember { mutableStateOf(ServiceFormState(setup)) }
    val kind = setup.kind
    val info = if (kind == ServiceKind.Search) "Lets Agents with the Web tools search the web, and read pages as clean text."
        else "Tells your phone when an Agent needs an Approval or an answer, replies, or a Routine has news, while the app isn't open."
    DetailPane(p.back, kind.title(), error = p.ui.error, info = info, trailing = {
        FormActions("Save", form.editable, p.ui.state.saved, onSave = { p.actions.onSaveService(form.saveParams()) })
    }) {
        FormField("Provider") {
            Picker((listOf<String?>(null) + setup.providers.map { it.id } + listOfNotNull(form.provider)).distinct(), form.provider,
                { id -> setup.providers.firstOrNull { it.id == id }?.name ?: id ?: "Off" }, { form = form.select(it) })
        }
        if (!form.editable) Text(if (form.definition == null) "This provider is not installed on the Server." else "Update this client to configure this provider.")
        else key(form.provider) {
            form.fields.forEach { field ->
                key(field.key) { ProviderField(field, form, p, { update -> form = update(form) }) }
            }
        }
        if (form.provider != null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            ActionButton(Lucide.PlugZap, if (kind == ServiceKind.Search) "Test search" else "Send test",
                { p.actions.onTestService(form.testParams()) }, enabled = form.editable && !p.ui.state.testing)
            if (p.ui.state.testing) Spinner()
        }
        p.ui.state.serviceTest?.let { t ->
            if (t.error != null) Pill("Didn't work: ${t.error}", tone = Tone.Danger, icon = Lucide.CircleX)
            else Pill("Works: ${t.summary}", tone = Tone.Success, icon = Lucide.CircleCheck)
        }
    }
}

@Composable
private fun ProviderField(field: SettingsField, form: ServiceFormState, p: Pane, change: ((ServiceFormState) -> ServiceFormState) -> Unit) {
    val value = form.value(field.key)
    val effective = form.effectiveValue(field.key)
    val placeholder = field.placeholder.ifEmpty { field.defaultValue.orEmpty() }
    val input = remember { TextFieldState(value) }
    val current by rememberUpdatedState(value)
    val update by rememberUpdatedState<(String) -> Unit>({ value -> change { it.change(field.key, value) } })
    LaunchedEffect(value) { if (input.text.toString() != value) input.setTextAndPlaceCursorAtEnd(value) }
    LaunchedEffect(input) { snapshotFlow { input.text.toString() }.collect { if (it != current) update(it) } }
    val uri = LocalUriHandler.current
    val link = form.link(field.key)
    val configured = field.sensitive && form.configured(field.key)
    val removing = form.secretUpdate(field.key) == SecretUpdate.Remove
    val actions: (@Composable () -> Unit)? = if (field.randomPrefix != null || field.link != null) {
        {
            Row {
                field.randomPrefix?.let { HeaderButton(Lucide.Dices, "Generate ${field.label}", { change { it.generate(field.key) } }) }
                field.link?.let { HeaderButton(Lucide.ExternalLink, "Open ${field.label}", { link?.let(uri::openUri) }, enabled = link != null) }
            }
        }
    } else null
    FormField(field.label, field.description) {
        when {
            field.sensitive -> SecretField(p, { value -> change { it.change(field.key, value) } }) { trailing ->
                Input(input, if (configured && !removing) "Saved; enter a replacement" else placeholder,
                    secret = !isReference(input.text.toString()), trailing = {
                        Row {
                            trailing?.invoke()
                            actions?.invoke()
                        }
                    })
            }
            field.type == SettingsFieldType.Boolean -> Checkbox(effective == "true", { update(it.toString()) }) { Text(if (effective == "true") "On" else "Off") }
            field.type == SettingsFieldType.Choice -> Picker(field.choices, effective, { it }, { update(it) })
            else -> Input(input, placeholder, trailing = actions)
        }
        if (configured) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (removing) "Will be removed on save" else "Saved on the Server")
                HeaderButton(if (removing) Lucide.Undo2 else Lucide.Trash2, if (removing) "Keep saved value" else "Remove saved value", {
                    change { if (removing) it.keepSecret(field.key) else it.removeSecret(field.key) }
                })
            }
        }
    }
}
