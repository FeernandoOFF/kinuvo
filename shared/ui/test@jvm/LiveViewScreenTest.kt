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
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.X

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
        val owner = BrowserOwner("Fern")
        val ref = ScreenRef("browser", "tab", owner)
        val previewRef = ref.copy(id = "preview")
        val missingRef = ref.copy(id = "missing")
        for ((width,height) in listOf(390 to 844, 1200 to 850)) for (mode in listOf("watch", "held", "request", "suspended", "reconnect", "offline", "empty")) {
            val control = when(mode) { "held" -> ScreenControl(1,"device"); "suspended" -> ScreenControl(1,"other",true); else -> ScreenControl() }
            val frame = ScreenFrame(ref,"sub","epoch",1,ScreenSample(Base64.encode(bytes),1280,800,"1280:800"))
            val info = ScreenInfo(ref,"Cedar — Your account",ScreenCapabilities(ScreenDelivery.Continuous,setOf("tap","scroll","type","key"),navigation=true,tabs=true,keys=listOf("Tab", "Escape", "Enter")),ScreenStatus.Ready,control, active=mode=="watch", currentUrl="https://cedar.example/account")
            val preview = frame.copy(screen = previewRef, subscription = "preview-sub")
            val state = LiveViewState(screens=if(mode=="empty") emptyList() else listOf(info, info.copy(ref = previewRef, title = "Cedar — Plans"), info.copy(ref = missingRef, title = "Loading tab")), thumbnails=mapOf(previewRef to preview), selected=ref, connected=mode!="offline", loaded=true, device="device", subscription=ScreenSubscription("sub","epoch"),frame=if(mode=="empty") null else frame,displayed=frame,owner=owner, frameReceivedAt="2026-10-29T16:00:00Z", pending=mode=="reconnect", requestReason=if(mode=="request") "Sign in to continue" else null)
            val drawn = mutableListOf<ScreenFrame>()
            ImageComposeScene(width*2,height*2,Density(2f), content={ HarnessTheme(dark=true) { androidx.compose.runtime.CompositionLocalProvider(kinuvo.ui.navigation.LocalHomeChrome provides kinuvo.ui.navigation.HomeChrome({}, width >= 760)) { LiveViewContent(state,LiveActions(drawn = { drawn.add(it) }), ({}).takeIf { width < 760 }) } } }).use { scene ->
                repeat(15) { scene.render(it*100_000_000L); Thread.sleep(10) }
                if (mode != "empty") assertEquals(listOf(frame), drawn)
                val tree = scene.semanticsOwners.flatMap { nodes(it.rootSemanticsNode) }
                assertEquals(width < 760, tree.any { "Back" in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() })
                if (width >= 760) assertTrue(tree.any { it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { label -> label.startsWith("Toggle sidebar") } })
                val texts = tree.flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }
                assertTrue(texts.isNotEmpty())
                assertTrue(if (mode == "empty") texts.any { it.startsWith("No screens") } else "Cedar — Your account" in texts)
                File("build/screens").apply { mkdirs() }.resolve("live-$width-$mode.png").writeBytes(scene.render(2_000_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes)
                if (mode == "watch") {
                    val tabs = tree.first { it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { it == "Screens" } && it.config.getOrNull(SemanticsActions.OnClick) != null }
                    tabs.config[SemanticsActions.OnClick].action!!.invoke()
                    repeat(15) { scene.render(3_000_000_000L + it * 100_000_000L); Thread.sleep(10) }
                    File("build/screens/live-$width-overview.png").writeBytes(scene.render(5_000_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes)
                    assertTrue(preview in drawn, "Subscribed thumbnail must render and acknowledge")
                    val overviewTree = scene.semanticsOwners.flatMap { nodes(it.rootSemanticsNode) }
                    assertEquals(3, overviewTree.count { "Live screen" in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() })
                    assertTrue(overviewTree.any { it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == "Loading tab" } })
                    if (width >= 760) {
                        tabs.config[SemanticsActions.OnClick].action!!.invoke()
                        repeat(5) { scene.render(6_000_000_000L + it * 100_000_000L) }
                        assertEquals(1, scene.semanticsOwners.flatMap { nodes(it.rootSemanticsNode) }.count { "Live screen" in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() })
                    }
                }
                if (mode == "held") {
                    val keyboard = tree.first { it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { it == "Keyboard" } && it.config.getOrNull(SemanticsActions.OnClick) != null }
                    assertTrue(keyboard.config[SemanticsActions.OnClick].action!!.invoke())
                    repeat(5) { scene.render(3_000_000_000L + it * 100_000_000L) }
                    assertTrue(scene.semanticsOwners.flatMap { nodes(it.rootSemanticsNode) }.any { it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == "Send" } })
                    File("build/screens/live-$width-keyboard.png").writeBytes(scene.render(4_000_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes)
                }
            }
        }
    }
    @Test fun dockedBrowserBesideChat() = javax.swing.SwingUtilities.invokeAndWait {
        val owner = BrowserOwner("Fern")
        val ref = ScreenRef("browser", "tab", owner)
        val info = ScreenInfo(ref, "Cedar — Your account", ScreenCapabilities(ScreenDelivery.Continuous, setOf("tap"), navigation = true, tabs = true), ScreenStatus.Ready, active = true, currentUrl = "https://cedar.example/account")
        val state = LiveViewState(screens = listOf(info, info.copy(ref = ref.copy(id = "plans"), title = "Cedar — Plans", active = false)), selected = ref, connected = true, loaded = true, owner = owner)
        ImageComposeScene(520 * 2, 800 * 2, Density(2f), content = { HarnessTheme(dark = true) {
            LiveViewContent(state, LiveActions(), null, docked = true, extra = listOf(kinuvo.ui.navigation.PageAction(Lucide.X, "Close Browser", {})))
        } }).use { scene ->
            repeat(5) { scene.render(it * 100_000_000L) }
            val tree = scene.semanticsOwners.flatMap { nodes(it.rootSemanticsNode) }
            assertTrue(tree.any { "Close Browser" in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() })
            assertTrue(tree.any { it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == "Cedar — Plans" } })
            assertFalse(tree.any { it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { it.startsWith("Toggle sidebar") } })
            File("build/screens").apply { mkdirs() }.resolve("live-docked.png").writeBytes(scene.render(1_000_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes)
        }
    }
    @Test fun gestureSurvivesFrameReplacementAndKeepsOriginalGeometry() = javax.swing.SwingUtilities.invokeAndWait {
        val image = java.awt.image.BufferedImage(100, 100, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val bytes = java.io.ByteArrayOutputStream().also { javax.imageio.ImageIO.write(image, "png", it) }.toByteArray()
        val first = ScreenFrame(ScreenRef("computer", "one"), "sub", "epoch", 1, ScreenSample(Base64.encode(bytes), 100, 100, "first"))
        val frame = androidx.compose.runtime.mutableStateOf(first)
        val sent = mutableListOf<Pair<ScreenFrame, List<ScreenAction>>>()
        ImageComposeScene(100, 100, Density(1f), content = { HarnessTheme { LiveCanvas(frame.value, true, { f, actions -> sent.add(f to actions) }, {}, {}) } }).use { scene ->
            repeat(15) { scene.render(it * 100_000_000L); Thread.sleep(10) }
            scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Press, androidx.compose.ui.geometry.Offset(20f,20f), timeMillis = 2000, type = androidx.compose.ui.input.pointer.PointerType.Touch)
            frame.value = first.copy(sequence = 2, sample = first.sample.copy(width = 200, geometry = "second"))
            repeat(15) { scene.render(2_100_000_000L + it * 10_000_000L); Thread.sleep(10) }
            scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Release, androidx.compose.ui.geometry.Offset(20f,20f), timeMillis = 2400, type = androidx.compose.ui.input.pointer.PointerType.Touch)
            scene.render(2_500_000_000L)
            assertEquals(listOf(first to listOf(ScreenAction(ScreenActionKind.Tap, x = 20, y = 20))), sent)
        }
    }
    @Test fun gestureCannotCrossScreenEpochOrControlGeneration() = javax.swing.SwingUtilities.invokeAndWait {
        val image = java.awt.image.BufferedImage(100, 100, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val bytes = java.io.ByteArrayOutputStream().also { javax.imageio.ImageIO.write(image, "png", it) }.toByteArray()
        val first = ScreenFrame(ScreenRef("computer", "one"), "sub", "epoch", 1, ScreenSample(Base64.encode(bytes), 100, 100, "geometry"))
        for (change in listOf("screen", "epoch", "generation", "interactive")) {
            val frame = androidx.compose.runtime.mutableStateOf(first)
            val generation = androidx.compose.runtime.mutableStateOf(1L)
            val interactive = androidx.compose.runtime.mutableStateOf(true)
            val sent = mutableListOf<ScreenFrame>()
            ImageComposeScene(100, 100, Density(1f), content = { HarnessTheme {
                LiveCanvas(frame.value, interactive.value, { f, _ -> sent.add(f) }, {}, {}, generation = generation.value)
            } }).use { scene ->
                repeat(15) { scene.render(it * 100_000_000L); Thread.sleep(10) }
                scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Press, androidx.compose.ui.geometry.Offset(20f,20f), timeMillis = 2000, type = androidx.compose.ui.input.pointer.PointerType.Touch)
                when (change) {
                    "screen" -> frame.value = first.copy(screen = first.screen.copy(id = "two"))
                    "epoch" -> frame.value = first.copy(epoch = "next")
                    "generation" -> generation.value++
                    else -> interactive.value = false
                }
                repeat(15) { scene.render(2_100_000_000L + it * 10_000_000L); Thread.sleep(10) }
                scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Release, androidx.compose.ui.geometry.Offset(20f,20f), timeMillis = 2400, type = androidx.compose.ui.input.pointer.PointerType.Touch)
                scene.render(2_500_000_000L)
                assertTrue(sent.isEmpty(), "Gesture crossed $change")
            }
        }
    }
    private fun nodes(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::nodes)
}
