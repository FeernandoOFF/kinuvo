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
import androidx.compose.ui.unit.sp
import com.composables.ui.theme.mutedColor
import com.composables.ui.components.*
import kinuvo.protocol.DeviceToolKind
import kinuvo.ui.design.*
import com.composables.icons.lucide.*

import kotlinx.coroutines.launch
import org.koin.compose.koinInject

internal fun DeviceToolKind.icon() = when (this) {
    DeviceToolKind.Contacts -> Lucide.Contact
    DeviceToolKind.Workouts -> Lucide.Dumbbell
    DeviceToolKind.Sleep -> Lucide.Moon
    DeviceToolKind.Computer -> Lucide.MousePointerClick
}

@Composable
internal fun DeviceToolsSettingsPane(p: Pane) {
    DetailPane(p.back, "This Device's tools", info = "Allow Agents on this Server to read selected data from this Device. Results are saved in the Session and sent to the model. OS permissions are also required.") {
        DeviceToolsGroup(empty = "Contacts are available on Android and iOS. Workouts and sleep are available on iOS.")
    }
}

/**
 * A switch for each tool this Device offers Agents, asking the OS for access when one is turned on; with [title], under it
 * and with what [note] says below. Shows [empty], if given, when the Device offers none.
 */
@Composable
internal fun DeviceToolsGroup(title: String? = null, note: String? = null, empty: String? = null) {
    val access = koinInject<DeviceAccess>()
    val state by access.state.collectAsState()
    val scope = rememberCoroutineScope()
    val authorize = rememberDeviceAuthorization(access.reader)
    DisposableEffect(access) { onDispose { access.cancelAuthorization() } }
    if (access.supported.isEmpty()) { empty?.let { Text(it) }; return }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        RowGroup(title) {
            access.supported.forEach { kind ->
                val about = when (kind) {
                    DeviceToolKind.Contacts -> "Look up names, phone numbers and emails. Read only."
                    DeviceToolKind.Workouts -> "Health workouts, duration, distance and energy. Routes are excluded."
                    DeviceToolKind.Sleep -> "Health sleep sessions and stages. Empty results can mean no data or no read access."
                    DeviceToolKind.Computer -> "See this Device's screen and tap, type, press keys and open apps, also in the background. " + access.computer?.requirement.orEmpty()
                }
                GroupRow(if (kind == DeviceToolKind.Computer) "Computer use" else kind.name, subtitle = if (state.busy == kind) "Requesting access…" else about,
                    icon = kind.icon(), chevron = false) {
                    Switch(kind in state.enabled, { enabled ->
                        if (state.busy == null) {
                            if (enabled) {
                                val attempt = access.authorizing(kind)
                                authorize(kind) { error -> scope.launch { access.authorized(attempt, error) } }
                            } else scope.launch { access.setEnabled(kind, false) }
                        }
                    })
                }
            }
        }
        note?.let { Text(it, Modifier.padding(horizontal = 6.dp), fontSize = 12.sp, color = color(mutedColor)) }
        state.error?.let { ErrorMessage(it) }
    }
}
