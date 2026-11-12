package kinuvo.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import com.composables.ui.theme.*
import kinuvo.ui.connection.TestGroup
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.*
import com.composables.ui.components.*
import kinuvo.app.settings.ProviderSettingsForm
import kinuvo.app.settings.saveParams
import kinuvo.app.settings.testParams
import kinuvo.protocol.*
import kinuvo.ui.connection.ServerLoading
import kinuvo.ui.design.*
import kinuvo.ui.design.HeaderButton

private fun ServiceKind.title() = if (this == ServiceKind.Search) "Web search" else "Notifications"

@Composable
internal fun ServiceSettingsPane(p: Pane, kind: ServiceKind) {
    val setup = p.ui.state.services[kind] ?: return ServerLoading(p, kind.title())
    key(setup) { ServiceForm(setup, p) }
}

@Composable
private fun ServiceForm(setup: ServiceSetup, p: Pane) {
    var form by remember { mutableStateOf(ProviderSettingsForm(setup)) }
    val kind = setup.kind
    val info = if (kind == ServiceKind.Search) "Lets Agents with the Web tools search the web, and read pages as clean text."
        else "Tells your phone when an Agent needs an Approval or an answer, replies, or a Routine has news, while the app isn't open."
    DetailPane(p.back, kind.title(), error = p.ui.error, info = info, trailing = {
        FormActions("Save", form.editable, p.ui.state.saved, onSave = { p.actions.onSaveService(form.saveParams(kind)) }, dirty = formChanged(form, p.ui.state.saved))
    }) {
        val push = kind == ServiceKind.Notifications && p.ui.capabilities.supports(ServerFeature.Push)
        RowGroup {
            if (push) SwitchRow("Push to paired Devices", form.push, { form = form.copy(push = it) }, pushedTo(p.ui.devices.devices), Lucide.Smartphone)
            PickerRow(if (push) "Also send through" else "Provider", (listOf<String?>(null) + setup.providers.map { it.id } + listOfNotNull(form.provider)).distinct(), form.provider,
                { id -> setup.providers.firstOrNull { it.id == id }?.name ?: id ?: "Off" }, { form = form.select(it) },
                if (kind == ServiceKind.Search) Lucide.Search else Lucide.Bell)
            if (!form.editable) GroupRow(if (form.definition == null) "This provider is not installed on the Server." else "Update this client to configure this provider.", tint = color(mutedColor))
        }
        ProviderSettingsFields("Settings", form, p) { update -> form = update(form) }
        if (form.provider != null || push && form.push) TestGroup(if (p.ui.state.testing) "Testing…" else if (kind == ServiceKind.Search) "Test search" else "Send test notification",
            form.editable && !p.ui.state.testing, { p.actions.onTestService(form.testParams(kind)) },
            p.ui.state.serviceTest?.let { t -> t.error?.let { "Didn't work: $it" } to "Works: ${t.summary}" })
    }
}

private fun pushedTo(devices: List<PairedDeviceInfo>?) = devices?.filter { it.push }?.map { it.name }?.let {
    if (it.isEmpty()) "No paired phone has allowed Notifications yet" else "Reaches " + it.joinToString()
}

/** The picked provider's settings in [form], once its schema is supported; [change] updates the form. */
@Composable
internal fun ProviderSettingsFields(title: String, form: ProviderSettingsForm, p: Pane, change: ((ProviderSettingsForm) -> ProviderSettingsForm) -> Unit) {
    if (form.editable && form.fields.isNotEmpty()) RowGroup(title) {
        key(form.provider) {
            form.fields.forEach { field ->
                key(field.key) { ProviderField(field, form, p, change) }
            }
        }
    }
}

@Composable
private fun ProviderField(field: SettingsField, form: ProviderSettingsForm, p: Pane, change: ((ProviderSettingsForm) -> ProviderSettingsForm) -> Unit) {
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
                field.randomPrefix?.let { FieldButton(Lucide.Dices, "Generate ${field.label}", { change { it.generate(field.key) } }) }
                field.link?.let { FieldButton(Lucide.ExternalLink, "Open ${field.label}", { link?.let(uri::openUri) }, enabled = link != null) }
            }
        }
    } else null
    when {
        field.type == SettingsFieldType.Boolean -> SwitchRow(field.label, effective == "true", { update(it.toString()) }, subtitle = field.description)
        field.type == SettingsFieldType.Choice -> PickerRow(field.label, field.choices, effective, { it }, { update(it) }, subtitle = field.description)
        else -> FieldRow(field.label, field.description) {
            if (field.sensitive) SecretField(p, { value -> change { it.change(field.key, value) } }) { trailing ->
                Input(input, if (configured && !removing) "Saved; enter a replacement" else placeholder,
                    secret = !isReference(input.text.toString()), trailing = {
                        Row {
                            trailing?.invoke()
                            actions?.invoke()
                        }
                    })
            } else Input(input, placeholder, trailing = actions)
            if (configured) Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (removing) Lucide.CircleAlert else Lucide.CircleCheck, Modifier.size(14.dp), tint = color(if (removing) warningColor else successColor))
                Text(if (removing) "  Removed when you save" else "  Saved on the Server", Modifier.weight(1f), style = TextStyles.secondary, color = color(mutedColor))
                if (removing) Button({ change { it.keepSecret(field.key) } }, style = ButtonStyle.Ghost, buttonSize = ButtonSize.Small) { Text("Keep") }
                else Button({ change { it.removeSecret(field.key) } }, style = ButtonStyle.Ghost, buttonSize = ButtonSize.Small, contentColor = color(destructiveColor)) { Text("Remove") }
            }
        }
    }
}
