package kinuvo.ui.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.muazkadan.rivecmp.RiveDesktop

@Composable
internal actual fun initializeRive(): Boolean = remember { runCatching { RiveDesktop.init() }.isSuccess }
