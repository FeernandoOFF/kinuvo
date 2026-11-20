package kinuvo.ui

import androidx.compose.runtime.Composable
import java.awt.Desktop
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

@Composable
internal actual fun rememberOpenInBrowser(): ((String) -> Unit)? =
    if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) null
    else { html -> openHtml(html) }

/** Writes [html] to a file only this user can read, kept until the app quits, and opens it. */
private fun openHtml(html: String) {
    val file = runCatching { Files.createTempFile("kinuvo-artifact-", ".html", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))) }
        .getOrElse { Files.createTempFile("kinuvo-artifact-", ".html") }
    Files.writeString(file, html)
    file.toFile().deleteOnExit()
    Desktop.getDesktop().browse(file.toUri())
}
