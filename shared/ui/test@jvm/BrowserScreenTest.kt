package kinuvo.ui

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import kinuvo.app.browser.BrowserState
import kinuvo.protocol.*
import kinuvo.ui.browser.*
import kinuvo.ui.design.HarnessTheme
import java.io.File
import org.jetbrains.skia.EncodedImageFormat
import kotlin.io.encoding.Base64
import kotlin.test.*

class BrowserScreenTest {
    @Test fun liveViewRendersOnDesktopAndPhoneAndTakeoverControlsAreExclusive() = javax.swing.SwingUtilities.invokeAndWait {
        val proof = File(System.getenv("KINUVO_HOME") ?: System.getProperty("java.io.tmpdir"), "browser-runtime-proof/browser-proof.jpg")
        val screenshot = Base64.encode(if (proof.isFile) proof.readBytes() else java.io.ByteArrayOutputStream().also {
            javax.imageio.ImageIO.write(java.awt.image.BufferedImage(1280, 800, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", it)
        }.toByteArray())
        for ((width, height) in listOf(1100 to 800, 390 to 844)) for (takeover in listOf(false, true)) {
            val tab = BrowserTab("tab", "agent", "session", "Browser proof", "http://127.0.0.1:8080", takeover, screenshot)
            val state = BrowserState(listOf(tab), tab.id, BrowserFrame(tab, "revision", screenshot = screenshot), loaded = true)
            ImageComposeScene(width * 2, height * 2, Density(2f), content = {
                HarnessTheme(dark = true) { BrowserContent(state, BrowserActions(), {}) }
            }).use { scene ->
                repeat(15) { scene.render(it * 100_000_000L); Thread.sleep(10) }
                val texts = scene.semanticsOwners.flatMap { text(it.rootSemanticsNode) }
                assertTrue(texts.contains(if (takeover) "Hand back" else "Take over"))
                assertEquals(takeover, texts.contains("Type"))
                File("build/screens").apply { mkdirs() }.resolve("browser-$width-${if (takeover) "takeover" else "watch"}.png")
                    .writeBytes(scene.render(2_000_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes)
            }
        }
    }
    private fun text(node: SemanticsNode): List<String> = node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } + node.children.flatMap(::text)
}
