package kinuvo.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import kinuvo.protocol.ComputerFrame
import kinuvo.protocol.ComputerInfo
import kinuvo.protocol.ComputerScreen
import kinuvo.ui.computer.ComputerDisplay
import kinuvo.ui.design.HarnessTheme
import java.io.ByteArrayOutputStream
import java.io.File
import org.jetbrains.skia.EncodedImageFormat
import kotlin.io.encoding.Base64
import kotlin.test.*

class ComputerScreenTest {
    private val screenshot = Base64.encode(ByteArrayOutputStream().also {
        javax.imageio.ImageIO.write(java.awt.image.BufferedImage(4, 2, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", it)
    }.toByteArray())

    @Test fun malformedDimensionsDoNotCrashDesktopLiveView() = javax.swing.SwingUtilities.invokeAndWait {
        var tapped: Pair<Int, Int>? = null
        val frame = ComputerFrame(ComputerInfo("desktop", "Mac mini", "macOS", true), ComputerScreen("c1", 1, 0, screenshot = screenshot))
        ImageComposeScene(1200, 1582, Density(1f), content = {
            HarnessTheme { ComputerDisplay(frame, true, { x, y -> tapped = x to y }, { _, _, _, _ -> }, Modifier.fillMaxSize()) }
        }).use { scene ->
            settle(scene)
            assertTrue(scene.hasImage("Mac mini"))
            scene.sendPointerEvent(PointerEventType.Press, Offset(600f, 791f), type = PointerType.Mouse, timeMillis = 2100)
            scene.sendPointerEvent(PointerEventType.Release, Offset(600f, 791f), type = PointerType.Mouse, timeMillis = 2200)
            assertEquals(2 to 1, tapped)
            File("build/screens").apply { mkdirs() }.resolve("computer-live-view-invalid-dimensions.png")
                .writeBytes(scene.render(2_300_000_000).encodeToData(EncodedImageFormat.PNG)!!.bytes)
        }
    }

    @Test fun switchingComputersClearsThePreviousImage() = javax.swing.SwingUtilities.invokeAndWait {
        val frame = mutableStateOf(ComputerFrame(ComputerInfo("phone", "Phone", "Android", true), ComputerScreen("c1", 4, 2, screenshot = screenshot)))
        ImageComposeScene(1200, 1582, Density(1f), content = {
            HarnessTheme { ComputerDisplay(frame.value, false, { _, _ -> }, { _, _, _, _ -> }, Modifier.fillMaxSize()) }
        }).use { scene ->
            settle(scene)
            assertTrue(scene.hasImage("Phone"))
            frame.value = ComputerFrame(ComputerInfo("desktop", "Mac mini", "macOS", true), ComputerScreen("c2", 1, 0))
            settle(scene)
            assertFalse(scene.hasImage("Mac mini"))
        }
    }

    private fun settle(scene: ImageComposeScene) {
        repeat(20) { scene.render(it * 100_000_000L); Thread.sleep(10) }
    }

    private fun ImageComposeScene.hasImage(name: String): Boolean {
        fun matches(node: SemanticsNode): Boolean = node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Screen of $name") == true || node.children.any(::matches)
        return semanticsOwners.any { matches(it.unmergedRootSemanticsNode) }
    }
}
