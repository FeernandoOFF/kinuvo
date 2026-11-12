package kinuvo.ui.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import org.jetbrains.compose.resources.decodeToImageBitmap
import ui.generated.resources.Res

/** The app icon, the Lilac Cloud avatar, for the desktop window and dock. */
@Composable
fun appIcon(): Painter? = bundledImage("files/icon.png")

/** The Lilac Cloud alone, for the system tray. */
@Composable
fun trayIcon(): Painter? = bundledImage("files/tray.png")

@Composable
private fun bundledImage(path: String) = produceState<Painter?>(null, path) { value = BitmapPainter(Res.readBytes(path).decodeToImageBitmap()) }.value
