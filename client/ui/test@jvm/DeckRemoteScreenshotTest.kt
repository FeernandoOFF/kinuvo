package kinuvo.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import com.composables.ui.theme.InteractionMode
import com.composables.ui.theme.LocalInteractionMode
import kinuvo.app.artifacts.DeckPosition
import kinuvo.app.artifacts.DeckSlide
import kinuvo.app.artifacts.DeckStatus
import kinuvo.ui.artifacts.DeckRemote
import kinuvo.ui.design.KinuvoTheme
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test

/** The presenter panel, rendered to `build/screens/deck-remote-*.png`. */
class DeckRemoteScreenshotTest {
    private val out = File("build/screens").apply { mkdirs() }
    private val notes = "Open with the problem: the talk runs on one deck and two screens.\n\n• Mention the demo is live\n• Pause for questions after the second slide\n\n" + "Keep going about the sandbox and the Server. ".repeat(12)
    private val deck = DeckStatus(DeckPosition(2, 0, null), 3, 14, notes, DeckSlide("Live demo", "Phone drives the desktop deck while notes follow along"))

    @Test fun renderDeckRemote() {
        render("deck-remote-phone", 390, 560, dark = true, touch = true) { DeckRemote(deck, {}, Modifier.fillMaxSize()) }
        render("deck-remote-phone-light", 390, 560, dark = false, touch = true) { DeckRemote(deck.copy(notes = "", next = null), {}, Modifier.fillMaxSize()) }
        render("deck-remote-desktop", 360, 700, dark = false, touch = false) { DeckRemote(deck, {}, Modifier.width(360.dp).fillMaxSize()) }
    }

    private fun render(name: String, width: Int, height: Int, dark: Boolean, touch: Boolean, content: @Composable () -> Unit) = javax.swing.SwingUtilities.invokeAndWait {
        ImageComposeScene(width * 2, height * 2, Density(2f), coroutineContext = Dispatchers.Main, content = {
            KinuvoTheme(dark = dark) { CompositionLocalProvider(LocalInteractionMode provides if (touch) InteractionMode.Touch else InteractionMode.Pointer) { content() } }
        }).use { scene ->
            repeat(5) { scene.render(it * 500_000_000L) }
            File(out, "$name.png").writeBytes(scene.render(3_000_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes)
        }
    }
}
