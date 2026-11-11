package kinuvo.ui.settings

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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
    RowGroup("OpenAI-compatible endpoint") {
        EndpointField("Provider ID", p.id) { onEdit(p.copy(id = it)) }
        EndpointField("Base URL", p.baseUrl) { onEdit(p.copy(baseUrl = it)) }
        EndpointField("API key (optional)", p.apiKey, secret = true) { onEdit(p.copy(apiKey = it)) }
        ActionRow(if (s.busy) "Loading…" else "Discover models", Lucide.RefreshCw, onDiscover, enabled = !s.busy && p.baseUrl.isNotBlank())
        if (s.models.isNotEmpty()) PickerRow("Model", s.models.map { it.id }, p.model, { it }, { onEdit(p.copy(model = it)) })
        if (s.manual) EndpointField("Model ID", p.model) { onEdit(p.copy(model = it)) }
        s.reason?.let { GroupBox { Text(it, style = TextStyles.secondary, color = color(warningColor)) } }
        s.error?.let { GroupBox { Text(it, color = color(destructiveColor)) } }
        ActionRow("Save custom provider", Lucide.Plus, onSave, enabled = s.saveable)
    }
}

@Composable
private fun EndpointField(label: String, value: String, secret: Boolean = false, onChange: (String) -> Unit) {
    val field = rememberTextFieldState(value)
    LaunchedEffect(value) { if (field.text.toString() != value) field.setTextAndPlaceCursorAtEnd(value) }
    val change by rememberUpdatedState(onChange)
    LaunchedEffect(field) { snapshotFlow { field.text.toString() }.collect { change(it) } }
    FieldRow(label) { Input(field, label, Modifier.semantics { contentDescription = "Endpoint $label" }, secret = secret) }
}
