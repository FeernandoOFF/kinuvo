package kinuvo.ui.design

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import kinuvo.protocol.Platform
import kinuvo.protocol.currentPlatform

internal actual val usesCommandKey: Boolean = currentPlatform == Platform.MacOS
internal actual val hasTouchKeyboard: Boolean = false

@OptIn(ExperimentalComposeUiApi::class)
actual fun Modifier.mouseHistoryButtons(onBack: () -> Unit, onForward: () -> Unit): Modifier =
    onPointerEvent(PointerEventType.Press, PointerEventPass.Initial) {
        when (it.button) {
            PointerButton.Back -> onBack()
            PointerButton.Forward -> onForward()
        }
    }

@Composable
actual fun BrowserBackHandler() {}
