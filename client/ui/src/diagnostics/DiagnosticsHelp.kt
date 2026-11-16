package kinuvo.ui.diagnostics

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import kotlinx.coroutines.delay
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.composables.icons.lucide.*
import com.composables.ui.components.*
import com.composables.ui.theme.*
import kinuvo.app.diagnostics.DiagnosticsViewModel
import kinuvo.ui.design.*
import org.koin.compose.viewmodel.koinViewModel
import kotlin.time.Clock

@Composable
internal fun DiagnosticsHelp(management: Boolean, wide: Boolean, serverKey: Any?) {
    var open by remember { mutableStateOf(false) }
    if (wide) ListRow(selected = false, onClick = { open = true }, leading = { Icon(Lucide.Bug, Modifier.size(16.dp)) }) {
        Text("Help & diagnostics", Modifier.weight(1f))
    } else ActionRow("Help & diagnostics", Lucide.Bug, { open = true })
    if (!open) return
    val vm = koinViewModel<DiagnosticsViewModel>()
    val state by vm.state.collectAsState()
    var includeServer by remember(serverKey) { mutableStateOf(false) }
    val now by produceState(Clock.System.now().toEpochMilliseconds()) {
        while (true) { delay(1000); value = Clock.System.now().toEpochMilliseconds() }
    }
    LaunchedEffect(management, serverKey) { vm.refresh(management) }
    Dialog(onDismissRequest = { if (!state.busy) open = false }) {
        Column(Modifier.widthIn(max = 560.dp).heightIn(max = 640.dp).clip(RoundedCornerShape(24.dp)).verticalScroll(rememberScrollState()).background(color(backgroundColor)).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Help & diagnostics", style = TextStyles.title)
            Text("Save recent logs, crash details and Agent trace metadata. Known secrets are redacted, but logs may still contain private information. Review the ZIP before sharing.", style = TextStyles.secondary)
            if (state.local.available) RowGroup("This Device") {
                val recording = state.local.recordingUntil > now
                ActionRow(if (recording) "Stop recording" else "Record debug logs", Lucide.Bug,
                    { vm.record(false, if (recording) 0 else 15) }, enabled = !state.busy,
                    subtitle = "Stops after 15 minutes. Includes its local Server.")
            }
            if (management && state.server.available) RowGroup("Selected Server") {
                SwitchRow("Include Server diagnostics", includeServer, { includeServer = it }, subtitle = "Collected separately using your management access.")
                val recording = state.server.recordingUntil > now
                ActionRow(if (recording) "Stop Server recording" else "Record Server logs", Lucide.Server,
                    { vm.record(true, if (recording) 0 else 15) }, enabled = !state.busy, subtitle = "Debug recording stops after 15 minutes.")
            }
            state.error?.let { Text(it, color = color(destructiveColor)) }
            state.saved?.let { Text("Saved to $it. Open the ZIP and review its files before attaching it to an issue or email.", style = TextStyles.secondary) }
            RowGroup {
                ActionRow(if (state.busy) "Collecting…" else "Export diagnostics…", Lucide.Download, { vm.export(includeServer && management) }, enabled = !state.busy && state.local.available)
                if (!state.local.available) GroupRow("Diagnostic export is currently available in the desktop app and CLI.")
                ActionRow("Close", Lucide.X, { open = false }, enabled = !state.busy)
            }
        }
    }
}
