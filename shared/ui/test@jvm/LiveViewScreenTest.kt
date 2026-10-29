package kinuvo.ui

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import androidx.compose.ui.semantics.*
import kinuvo.app.screen.LiveViewState
import kinuvo.protocol.*
import kinuvo.ui.screen.*
import kinuvo.ui.design.HarnessTheme
import java.io.File
import org.jetbrains.skia.EncodedImageFormat
import kotlin.io.encoding.Base64
import kotlin.test.*

class LiveViewScreenTest {
    @Test fun phoneAndDesktopStates() = javax.swing.SwingUtilities.invokeAndWait {
        val image = java.awt.image.BufferedImage(1280, 800, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.color = java.awt.Color(238,244,243); g.fillRect(0,0,1280,800)
        g.color = java.awt.Color(14,77,74); g.fillRect(0,0,1280,100)
        g.color = java.awt.Color.WHITE; g.font = java.awt.Font("SansSerif", 0, 28); g.drawString("Cedar · Your account", 50, 62)
        g.fillRoundRect(380,230,520,350,18,18)
        g.color = java.awt.Color(14,77,74); g.drawString("Sign in to continue", 430,300)
        g.color = java.awt.Color(210,220,218); g.fillRoundRect(430,340,420,60,8,8)
        g.color = java.awt.Color(14,77,74); g.fillRoundRect(430,440,420,60,8,8)
        g.color = java.awt.Color.WHITE; g.drawString("Continue", 560,480); g.dispose()
        val bytes = java.io.ByteArrayOutputStream().also { javax.imageio.ImageIO.write(image,"png",it) }.toByteArray()
        val owner = BrowserOwner("Fern", "Travel planning")
        val ref = ScreenRef("browser", "tab", owner)
        for ((width,height) in listOf(390 to 844, 1200 to 850)) for (mode in listOf("watch", "held", "suspended", "offline", "empty")) {
            val control = when(mode) { "held" -> ScreenControl(1,"device"); "suspended" -> ScreenControl(1,"other",true); else -> ScreenControl() }
            val frame = ScreenFrame(ref,"sub","epoch",1,ScreenSample(Base64.encode(bytes),1280,800,"1280:800"))
            val info = ScreenInfo(ref,"Cedar — Your account",ScreenCapabilities(ScreenDelivery.Continuous,setOf("tap","scroll","type","key"),navigation=true,tabs=true),ScreenStatus.Ready,control)
            val state = LiveViewState(screens=if(mode=="empty") emptyList() else listOf(info), selected=ref, connected=mode!="offline", loaded=true, device="device", subscription=ScreenSubscription("sub","epoch"),frame=if(mode=="empty") null else frame,displayed=frame,owner=owner)
            val drawn = mutableListOf<ScreenFrame>()
            ImageComposeScene(width*2,height*2,Density(2f), content={ HarnessTheme(dark=true) { LiveViewContent(state,LiveActions(drawn = { drawn.add(it) }),{}) } }).use { scene ->
                repeat(15) { scene.render(it*100_000_000L); Thread.sleep(10) }
                if (mode != "empty") assertEquals(listOf(frame), drawn)
                val tree = scene.semanticsOwners.flatMap { nodes(it.rootSemanticsNode) }
                val texts = tree.flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }
                assertTrue("Live view" in texts)
                assertTrue(if (mode == "empty") texts.any { it.startsWith("No screens") } else "Cedar — Your account" in texts)
                File("build/screens").apply { mkdirs() }.resolve("live-$width-$mode.png").writeBytes(scene.render(2_000_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes)
                if (mode == "held") {
                    val keyboard = tree.first { it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == "Keyboard" } && it.config.getOrNull(SemanticsActions.OnClick) != null }
                    assertTrue(keyboard.config[SemanticsActions.OnClick].action!!.invoke())
                    repeat(5) { scene.render(3_000_000_000L + it * 100_000_000L) }
                    assertTrue(scene.semanticsOwners.flatMap { nodes(it.rootSemanticsNode) }.any { it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == "Type" } })
                    File("build/screens/live-$width-keyboard.png").writeBytes(scene.render(4_000_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes)
                }
            }
        }
    }
    private fun nodes(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::nodes)
}
