package kinuvo.ui.diagnostics

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.FileText
import com.composables.icons.lucide.Lucide
import com.composables.ui.components.*
import com.composables.ui.theme.*
import kinuvo.app.diagnostics.LogsState
import kinuvo.app.diagnostics.LogsViewModel
import kinuvo.ui.design.*
import kinuvo.ui.rememberShareFile
import org.koin.compose.viewmodel.koinViewModel

/** Settings' About row that zips this Device's logs and crash files and hands the ZIP to the platform's share sheet. */
@Composable
internal fun ShareLogsRow(wide: Boolean) {
    val vm = koinViewModel<LogsViewModel>()
    if (!vm.available) return
    val state by vm.state.collectAsState()
    ShareBundle(vm, state)
    val label = if (state.busy) "Collecting logs…" else "Share logs"
    if (wide) ListRow(selected = false, onClick = vm::share, leading = { Icon(Lucide.FileText, Modifier.size(16.dp)) }) {
        Text(label, Modifier.weight(1f), singleLine = true)
    } else ActionRow(label, Lucide.FileText, vm::share, enabled = !state.busy)
    state.error?.let { Text(it, color = color(destructiveColor), style = TextStyles.caption) }
}

/** Offers to share logs when the app crashed in its last session. */
@Composable
internal fun CrashPrompt() {
    val vm = koinViewModel<LogsViewModel>()
    val state by vm.state.collectAsState()
    ShareBundle(vm, state)
    ConfirmPopover(
        state.crashed, "Kinuvo closed unexpectedly",
        "Share its logs to help find the problem? The ZIP holds recent app events and the crash, not your chats, files or sign-ins. " +
            "Review it before sending; you can also share logs later from Settings.",
        "Share logs", destructive = false, vm::dismissCrash, vm::share,
    )
}

@Composable
private fun ShareBundle(vm: LogsViewModel, state: LogsState) {
    val share = rememberShareFile()
    LaunchedEffect(state.bundle) { if (state.bundle != null) vm.takeBundle()?.let { share?.invoke(it) } }
}
