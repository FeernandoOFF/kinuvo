package kinuvo.ui.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import kinuvo.app.native.StateObservation
import kinuvo.app.native.observe
import kotlinx.coroutines.flow.MutableStateFlow

private val hidden = MutableStateFlow(0)

@Composable
actual fun HideSystemBars() {
    DisposableEffect(Unit) {
        hidden.value++
        onDispose { hidden.value-- }
    }
}

/** The SwiftUI app hides the status bar and home indicator while this reports true. */
fun observeSystemBarsHidden(onChange: (Boolean) -> Unit): StateObservation =
    hidden.observe { onChange(it > 0) }
