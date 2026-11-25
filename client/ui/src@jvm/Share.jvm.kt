package kinuvo.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kinuvo.protocol.device.Platform
import kinuvo.protocol.device.currentPlatform
import java.io.File

@Composable
internal actual fun rememberShare(): ((String) -> Unit)? = null

@Composable
internal actual fun rememberShareFile(): ((String) -> Unit)? = remember { { path -> fileRevealer(path) } }

/** Tests swap this to keep from opening a file manager. */
internal var fileRevealer: (String) -> Unit = ::revealInFileManager

private fun revealInFileManager(path: String) {
    runCatching {
        ProcessBuilder(when (currentPlatform) {
            Platform.MacOS -> listOf("open", "-R", path)
            Platform.Windows -> listOf("explorer.exe", "/select,$path")
            else -> listOf("xdg-open", File(path).parent)
        }).start()
    }
}
