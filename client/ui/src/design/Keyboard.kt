package kinuvo.ui.design

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController

internal expect val hasTouchKeyboard: Boolean

@Composable
internal fun Modifier.dismissMobileKeyboard(): Modifier {
    if (!hasTouchKeyboard) return this
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val scrolling = remember(focus, keyboard) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y > 0) {
                    focus.clearFocus()
                    keyboard?.hide()
                }
                return Offset.Zero
            }
        }
    }
    return nestedScroll(scrolling).pointerInput(focus, keyboard) {
        detectTapGestures(onTap = { focus.clearFocus(); keyboard?.hide() })
    }
}
