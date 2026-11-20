package kinuvo.ui.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

/** Capture layers would contain the transparent hole instead of exposing the native view below Compose. */
internal class NativeViews {
    private var count by mutableIntStateOf(0)
    val active get() = count > 0

    fun attach() { count++ }
    fun detach() { count-- }
}

internal val LocalNativeViewRegistry = staticCompositionLocalOf<NativeViews?> { null }

@Composable
internal fun backdropBlurEnabled() = LocalBackdropBlur.current && LocalNativeViewRegistry.current?.active != true
