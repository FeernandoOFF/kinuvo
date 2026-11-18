package kinuvo.ui.settings

import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import com.composables.icons.lucide.*
import com.composables.ui.components.*
import com.composables.ui.theme.*
import kinuvo.app.settings.EndpointViewModel
import kinuvo.app.settings.EndpointState
import kinuvo.protocol.model.CustomEndpointParams
import kinuvo.ui.design.*
import org.koin.compose.viewmodel.koinViewModel

@Composable
internal fun EndpointForm(vm: EndpointViewModel = koinViewModel(), onSaved: (String, String) -> Unit) {
    val s by vm.state.collectAsState()
    EndpointContent(s, vm::edit, vm::discover) { vm.save(onSaved) }
}

@Composable
internal fun EndpointContent(s: EndpointState, onEdit: (CustomEndpointParams) -> Unit, onDiscover: () -> Unit, onSave: () -> Unit) {
    val p = s.params
    // Connect first; the model and name only matter once the server answered.
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.space8)) {
        Column(Modifier.padding(start = Spacing.space6), verticalArrangement = Arrangement.spacedBy(Spacing.space2)) {
            SectionLabel("Custom endpoint")
            Text("LM Studio, Ollama, vLLM or any server with an OpenAI-compatible API.", Modifier.padding(horizontal = Spacing.space10), style = TextStyles.caption, color = color(mutedColor))
        }
        RowGroup {
            GroupBox {
                EndpointField("Server URL", p.baseUrl, "http://localhost:1234/v1") { onEdit(p.copy(baseUrl = it)) }
                EndpointField("API key", p.apiKey, "Only if the server needs one", optional = true, secret = true) { onEdit(p.copy(apiKey = it)) }
                Button(onClick = onDiscover, modifier = Modifier.fillMaxWidth(), enabled = !s.busy && p.baseUrl.isNotBlank(),
                    style = if (s.discovered) ButtonStyle.Outlined else ButtonStyle.Primary) {
                    if (s.busy) Spinner(Modifier.size(15.dp)) else Icon(Lucide.RefreshCw, Modifier.size(15.dp))
                    Spacer(Modifier.width(Spacing.space6))
                    Text(when { s.busy -> "Connecting…"; s.discovered -> "Find models again"; else -> "Connect and find models" }, singleLine = true)
                }
                s.reason?.let { Text(it, style = TextStyles.secondary, color = color(warningColor)) }
            }
        }
        if (s.discovered) RowGroup {
            if (s.models.isNotEmpty()) PickerRow("Model", s.models.map { it.id }, p.model, { it }, { onEdit(p.copy(model = it)) }, Lucide.Cpu,
                subtitle = "${s.models.size} found")
            GroupBox {
                if (s.manual) EndpointField("Model ID", p.model, "e.g. llama-3.1-8b") { onEdit(p.copy(model = it)) }
                EndpointField("Name", p.id, "e.g. lmstudio", hint = "Shown as the provider in model lists.") { onEdit(p.copy(id = it)) }
                s.error?.let { Text(it, style = TextStyles.secondary, color = color(destructiveColor)) }
                Button(onClick = onSave, modifier = Modifier.fillMaxWidth(), enabled = s.saveable, style = ButtonStyle.Primary) {
                    Icon(Lucide.Plus, Modifier.size(15.dp))
                    Spacer(Modifier.width(Spacing.space6))
                    Text("Add provider", singleLine = true)
                }
            }
        }
    }
}

@Composable
private fun EndpointField(label: String, value: String, placeholder: String, optional: Boolean = false, secret: Boolean = false, hint: String? = null, onChange: (String) -> Unit) {
    val field = rememberTextFieldState(value)
    LaunchedEffect(value) { if (field.text.toString() != value) field.setTextAndPlaceCursorAtEnd(value) }
    val change by rememberUpdatedState(onChange)
    LaunchedEffect(field) { snapshotFlow { field.text.toString() }.collect { change(it) } }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.space4)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.space6)) {
            Text(label, style = TextStyles.secondary, color = color(mutedColor))
            if (optional) Text("Optional", style = TextStyles.caption, color = color(mutedColor))
        }
        Input(field, placeholder, Modifier.semantics { contentDescription = "Endpoint $label" }, secret = secret)
        hint?.let { Text(it, style = TextStyles.caption, color = color(mutedColor)) }
    }
}
