package kinuvo.ui.device

import kinuvo.app.connection.*
import kinuvo.app.device.*
import kinuvo.app.agent.*
import kinuvo.ui.agent.*
import kinuvo.ui.*

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.composables.ui.components.*
import kinuvo.protocol.DeviceToolKind
import kinuvo.ui.design.ErrorMessage
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@Composable
internal fun DeviceToolsSettingsPane(p: Pane) {
    val access = koinInject<DeviceAccess>()
    val state by access.state.collectAsState()
    val scope = rememberCoroutineScope()
    val authorize = rememberDeviceAuthorization(access.reader)
    DisposableEffect(access) { onDispose { access.cancelAuthorization() } }
    DetailPane(p.back, "This Device's tools", info = "Allow Agents on this Server to read selected data from this Device. Results are saved in the Session and sent to the model. OS permissions are also required.") {
        if (access.supported.isEmpty()) Text("Contacts are available on Android and iOS. Workouts and sleep are available on iOS.")
        access.supported.forEach { kind ->
            DetailGroup(kind.name) {
                Text(when (kind) {
                    DeviceToolKind.Contacts -> "Look up names, phone numbers and emails. Contacts are read only."
                    DeviceToolKind.Workouts -> "Read Health workouts, duration, distance and energy. Routes are excluded."
                    DeviceToolKind.Sleep -> "Read Health sleep sessions and available stages. Empty results can mean no data or no read access."
                    DeviceToolKind.Computer -> "Let Agents see this Device's screen and tap, type, press keys and open apps, also while Kinuvo is in the background. " + access.computer?.requirement.orEmpty()
                })
                Checkbox(kind in state.enabled, { enabled ->
                    if (state.busy == null) {
                        if (enabled) {
                            val attempt = access.authorizing(kind)
                            authorize(kind) { error -> scope.launch { access.authorized(attempt, error) } }
                        } else scope.launch { access.setEnabled(kind, false) }
                    }
                }) { Text(if (state.busy == kind) "Requesting access…" else if (kind == DeviceToolKind.Computer) "Allow computer use" else "Allow ${kind.name.lowercase()}") }
            }
        }
        state.error?.let { ErrorMessage(it) }
    }
}
