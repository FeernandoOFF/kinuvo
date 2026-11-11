package kinuvo.ui.design

import androidx.compose.runtime.Composable
import dev.muazkadan.rivecmp.RiveDesktop

private val riveReady by lazy { runCatching { RiveDesktop.init() }.isSuccess }

@Composable
internal actual fun initializeRive(): Boolean = riveReady
