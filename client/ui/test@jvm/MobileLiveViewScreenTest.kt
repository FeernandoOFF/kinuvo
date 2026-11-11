package kinuvo.ui

import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.scene.ComposeScenePointer
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import kinuvo.app.screen.LiveViewState
import kinuvo.protocol.*
import kinuvo.protocol.browser.*
import kinuvo.protocol.screen.*
import kinuvo.ui.design.HarnessTheme
import kinuvo.ui.screen.*
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlin.io.encoding.Base64
import kotlin.test.*
import org.jetbrains.skia.EncodedImageFormat

class MobileLiveViewScreenTest {
    private fun sample(width: Int = 1280, height: Int = 800): ScreenSample {
        val image = java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_RGB)
        image.createGraphics().apply {
            color = java.awt.Color(32, 38, 42); fillRect(0, 0, width, height)
            color = java.awt.Color(92, 150, 142); fillRect(width / 4, height / 4, width / 2, height / 2); dispose()
        }
        val bytes = java.io.ByteArrayOutputStream().also { javax.imageio.ImageIO.write(image, "png", it) }.toByteArray()
        return ScreenSample(Base64.encode(bytes), width, height, "geometry")
    }
    private fun nodes(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::nodes)
    private fun tree(scene: ImageComposeScene) = scene.semanticsOwners.flatMap { nodes(it.rootSemanticsNode) }
    // Decoding scenes use Dispatchers.Main so the decode resumes when render flushes it on this thread, not on a worker racing composition.
    private fun awaitFrame(scene: ImageComposeScene) {
        // A deadline rather than a count: the first decode can take seconds on a cold CI runner.
        val deadline = System.nanoTime() + 15_000_000_000L
        while (System.nanoTime() < deadline) {
            scene.render(System.nanoTime())
            if (tree(scene).any { "Live screen" in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }) return
            Thread.sleep(10)
        }
        fail("Frame must be decoded before input")
    }
    private fun click(scene: ImageComposeScene, label: String) {
        val node = tree(scene).first { label in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() || it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == label } }
        assertTrue(node.config[SemanticsActions.OnClick].action!!.invoke(), label)
        repeat(8) { scene.render(System.nanoTime()); Thread.sleep(10) }
    }

    @Test fun mobileBrowserAndComputerShareMinimalChromeAndExplicitPaste() = javax.swing.SwingUtilities.invokeAndWait {
        for (browser in listOf(false, true)) {
            val owner = BrowserOwner("agent").takeIf { browser }
            val ref = ScreenRef(if (browser) "browser" else "computer", "one", owner)
            val frame = ScreenFrame(ref, "sub", "epoch", 1, sample())
            val info = ScreenInfo(ref, "Design", ScreenCapabilities(ScreenDelivery.Continuous, setOf("tap", "type", "scroll", "key"),
                navigation = browser, tabs = browser, keys = BrowserKeys, modifiers = BrowserModifiers, resize = browser), ScreenStatus.Ready, ScreenControl(1, "device"))
            val state = mutableStateOf(LiveViewState(screens = listOf(info), selected = ref, connected = true, loaded = true, device = "device", owner = owner, frame = frame, displayed = frame,
                presentation = BrowserPresentation(headless = false, runningHeadless = false).takeIf { browser }))
            var reads = 0
            val inputs = mutableListOf<ScreenAction>()
            @Suppress("DEPRECATION")
            val clipboard = object : androidx.compose.ui.platform.ClipboardManager {
                override fun getText(): AnnotatedString { reads++; return AnnotatedString("Pasted text") }
                override fun setText(annotatedString: AnnotatedString) = Unit
            }
            @Suppress("DEPRECATION")
            ImageComposeScene(390, 844, Density(1f), content = { HarnessTheme(dark = true) {
                CompositionLocalProvider(androidx.compose.ui.platform.LocalClipboardManager provides clipboard) {
                    LiveViewContent(state.value, LiveActions(input = { _, actions -> inputs.addAll(actions) }), {})
                }
            } }).use { scene ->
                repeat(15) { scene.render(it * 100_000_000L); Thread.sleep(10) }
                assertEquals(0, reads)
                assertTrue(tree(scene).any { "Keyboard" in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() })
                assertFalse(tree(scene).any { "Address" in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() })
                assertFalse(tree(scene).any { "Browser options" in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() })
                click(scene, "Screen options")
                assertEquals(browser, tree(scene).any { it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { t -> t.text == "Show browser window" } })
                click(scene, "Trackpad mode")
                assertTrue(tree(scene).any { "Local pointer" in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() })
                click(scene, "Screen options")
                assertTrue(tree(scene).any { it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { t -> t.text == "Recenter pointer" } })
                File("build/screens").apply { mkdirs() }.resolve("mobile-${if (browser) "browser" else "computer"}-menu.png").writeBytes(scene.render(System.nanoTime()).encodeToData(EncodedImageFormat.PNG)!!.bytes)
                click(scene, "Recenter pointer")
                click(scene, "Clipboard")
                assertEquals(!browser, tree(scene).any { it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { t -> t.text == "Copy on screen" } })
                click(scene, "Paste")
                assertEquals(1, reads)
                assertEquals(listOf(ScreenAction(ScreenActionKind.Type, text = "Pasted text")), inputs)
                click(scene, "Keyboard")
                val proxy = tree(scene).first { it.config.getOrNull(SemanticsActions.SetText) != null }
                assertTrue(proxy.boundsInRoot.height <= 1f, "Mobile text entry must stay hidden")
                click(scene, "Keys")
                File("build/screens/mobile-${if (browser) "browser" else "computer"}-keys.png").writeBytes(scene.render(System.nanoTime()).encodeToData(EncodedImageFormat.PNG)!!.bytes)
                click(scene, "Hide keys")
                File("build/screens/mobile-${if (browser) "browser" else "computer"}.png").writeBytes(scene.render(System.nanoTime()).encodeToData(EncodedImageFormat.PNG)!!.bytes)
                // A Browser's Tabs sit in the dock; a Computer's other Screens are in the options menu.
                if (browser) click(scene, "Tabs & address")
                else {
                    click(scene, "Screen options")
                    var screens = tree(scene).first { it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == "Computers" } }
                    while (screens.config.getOrNull(SemanticsActions.OnClick) == null) screens = screens.parent!!
                    assertTrue(screens.config[SemanticsActions.OnClick].action!!.invoke())
                    repeat(8) { scene.render(System.nanoTime()); Thread.sleep(10) }
                }
                if (browser) assertTrue(tree(scene).any { "Address" in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() })
                click(scene, "Close screen controls")
                state.value = state.value.copy(connected = false)
                repeat(5) { scene.render(System.nanoTime()) }
                assertFalse(tree(scene).any { "Local pointer" in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() })
            }
        }
    }

    @Test fun canvasGesturesNeverOpenKeyboardUntilKeyboardButtonIsSelected() = javax.swing.SwingUtilities.invokeAndWait {
        val ref = ScreenRef("browser", "one", BrowserOwner("agent"))
        val frame = ScreenFrame(ref, "sub", "epoch", 1, sample(1000, 500))
        val info = ScreenInfo(ref, "First", ScreenCapabilities(ScreenDelivery.Continuous, setOf("tap", "type", "scroll")), ScreenStatus.Ready, ScreenControl(1, "device"))
        val state = LiveViewState(screens = listOf(info), selected = ref, connected = true, device = "device", owner = ref.owner, frame = frame, displayed = frame)
        var shows = 0
        val keyboard = object : androidx.compose.ui.platform.SoftwareKeyboardController {
            override fun show() { shows++ }
            override fun hide() = Unit
        }
        val sent = mutableListOf<ScreenAction>()
        ImageComposeScene(390, 844, Density(1f), coroutineContext = Dispatchers.Main, content = { HarnessTheme {
            CompositionLocalProvider(androidx.compose.ui.platform.LocalSoftwareKeyboardController provides keyboard) {
                LiveViewContent(state, LiveActions(input = { _, actions -> sent.addAll(actions) }), {})
            }
        } }).use { scene ->
            awaitFrame(scene)
            fun event(kind: PointerEventType, x: Float, y: Float, time: Long) = scene.sendPointerEvent(kind, Offset(x, y), timeMillis = time, type = PointerType.Touch)
            event(PointerEventType.Press, 180f, 450f, 2000)
            event(PointerEventType.Release, 180f, 450f, 2050)
            event(PointerEventType.Press, 180f, 450f, 2100)
            event(PointerEventType.Move, 180f, 490f, 2150)
            event(PointerEventType.Release, 180f, 490f, 2200)
            repeat(5) { scene.render(System.nanoTime()) }
            assertTrue(sent.isNotEmpty(), "Gestures must reach the remote screen")
            assertEquals(0, shows)
            assertFalse(tree(scene).first { "Type into remote screen" in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }.config.getOrNull(SemanticsProperties.Focused) == true)
            click(scene, "Keyboard")
            assertTrue(shows > 0)
            click(scene, "Hide keyboard")
            assertFalse(tree(scene).first { "Type into remote screen" in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }.config.getOrNull(SemanticsProperties.Focused) == true)
        }
    }

    @Test fun tappingThumbnailImageSelectsTabAndDismissesPicker() = javax.swing.SwingUtilities.invokeAndWait {
        val owner = BrowserOwner("agent")
        val first = ScreenRef("browser", "one", owner)
        val second = first.copy(id = "two")
        val frame = ScreenFrame(first, "sub", "epoch", 1, sample())
        val info = ScreenInfo(first, "First", ScreenCapabilities(ScreenDelivery.Continuous, setOf("tap"), tabs = true), ScreenStatus.Ready)
        var state by mutableStateOf(LiveViewState(screens = listOf(info, info.copy(ref = second, title = "Second")), selected = first,
            connected = true, owner = owner, frame = frame, displayed = frame, thumbnails = mapOf(second to frame.copy(screen = second))))
        ImageComposeScene(390, 844, Density(1f), coroutineContext = Dispatchers.Main, content = { HarnessTheme {
            LiveViewContent(state, LiveActions(select = { state = state.copy(selected = it) }), {})
        } }).use { scene ->
            awaitFrame(scene)
            click(scene, "Tabs & address")
            repeat(30) { androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications(); scene.render(System.nanoTime()); Thread.sleep(10) }
            val tile = tree(scene).first { it.config.getOrNull(SemanticsActions.OnClick)?.label == "Select Second" }
            val thumbnail = nodes(tile).first { "Live screen" in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }
            val point = thumbnail.boundsInRoot.center
            scene.sendPointerEvent(PointerEventType.Press, point, timeMillis = 3000, type = PointerType.Touch)
            scene.sendPointerEvent(PointerEventType.Release, point, timeMillis = 3050, type = PointerType.Touch)
            repeat(10) { scene.render(System.nanoTime()); Thread.sleep(10) }
            assertEquals(second, state.selected)
            val deadline = System.nanoTime() + 2_000_000_000L
            while (tree(scene).any { "Close screen controls" in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() } && System.nanoTime() < deadline) {
                androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
                scene.render(System.nanoTime())
                Thread.sleep(10)
            }
            assertFalse(tree(scene).any { "Close screen controls" in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() })
        }
    }

    @Test fun browserHeadlessRestartRequiresConfirmationAndCanBeCancelled() = javax.swing.SwingUtilities.invokeAndWait {
        var state by mutableStateOf(LiveViewState(connected = true, presentation = BrowserPresentation(headless = false, runningHeadless = false)))
        val sent = mutableListOf<Pair<Boolean, Boolean>>()
        ImageComposeScene(390, 844, Density(1f), content = { HarnessTheme {
            BrowserWindowControls(state, LiveActions(presentation = { headless, hidden ->
                sent.add(headless to hidden)
                state = state.copy(presentation = BrowserPresentation(headless, hidden, runningHeadless = headless))
            }))
        } }).use { scene ->
            repeat(5) { scene.render(System.nanoTime()) }
            click(scene, "Browser options")
            click(scene, "Show browser window")
            assertTrue(sent.isEmpty())
            click(scene, "Cancel")
            assertTrue(sent.isEmpty())
            click(scene, "Browser options")
            click(scene, "Show browser window")
            click(scene, "Restart Chrome")
            assertEquals(listOf(true to false), sent)
            click(scene, "Browser options")
            click(scene, "Show browser window")
            assertEquals(1, sent.size)
            click(scene, "Restart Chrome")
            assertEquals(false to false, sent.last())
        }
    }

    @Test fun controlsExposeAndroidKeysAndDesktopShortcuts() = javax.swing.SwingUtilities.invokeAndWait {
        for (android in listOf(true, false)) {
            val ref = ScreenRef("computer", "one")
            val frame = ScreenFrame(ref, "sub", "epoch", 1, sample())
            val capabilities = ScreenCapabilities(ScreenDelivery.CaptureRefresh, setOf("tap", "key", "type"),
                keys = if (android) listOf("Back", "Home", "Recents") else listOf("Tab", "Escape", "Left", "Enter", "C"),
                modifiers = if (android) emptyList() else listOf("Command", "Shift"))
            val state = LiveViewState(screens = listOf(ScreenInfo(ref, "Computer", capabilities, ScreenStatus.Ready, ScreenControl(1, "device"))),
                selected = ref, connected = true, device = "device", frame = frame, displayed = frame)
            val sent = mutableListOf<ScreenAction>()
            ImageComposeScene(390, 844, Density(1f), content = { HarnessTheme {
                MobileKeyTray(state, LiveActions(input = { _, actions -> sent.addAll(actions) }))
            } }).use { scene ->
                repeat(5) { scene.render(System.nanoTime()) }
                if (android) {
                    click(scene, "Home")
                    assertEquals("Home", sent.single().key)
                } else {
                    click(scene, "Command")
                    click(scene, "C")
                    assertEquals("Command+C", sent.single().key)
                    click(scene, "Enter")
                    assertEquals("Enter", sent.last().key)
                }
            }
        }
    }

    @Test fun trackpadMovesLocallyClicksAtCursorAndRecenters() = javax.swing.SwingUtilities.invokeAndWait {
        val frame = ScreenFrame(ScreenRef("browser", "one", BrowserOwner("agent")), "sub", "epoch", 1, sample(1000, 500))
        var recenter by mutableIntStateOf(0)
        val sent = mutableListOf<Pair<ScreenFrame, List<ScreenAction>>>()
        ImageComposeScene(200, 100, Density(1f), coroutineContext = Dispatchers.Main, content = { HarnessTheme {
            LiveCanvas(frame, true, { f, actions -> sent.add(f to actions) }, {}, {}, trackpad = true, recenter = recenter)
        } }).use { scene ->
            awaitFrame(scene)
            fun event(kind: PointerEventType, x: Float, y: Float, time: Long) = scene.sendPointerEvent(kind, Offset(x, y), timeMillis = time, type = PointerType.Touch)
            event(PointerEventType.Press, 50f, 50f, 2000)
            event(PointerEventType.Move, 70f, 50f, 2100)
            event(PointerEventType.Release, 70f, 50f, 2200)
            assertTrue(sent.isEmpty())
            scene.render(2_300_000_000L)
            event(PointerEventType.Press, 20f, 20f, 2400)
            event(PointerEventType.Release, 20f, 20f, 2450)
            assertEquals(frame to listOf(ScreenAction(ScreenActionKind.Tap, x = 600, y = 250)), sent.single())
            recenter++
            repeat(5) { scene.render(2_500_000_000L + it * 10_000_000L) }
            event(PointerEventType.Press, 20f, 20f, 2700)
            event(PointerEventType.Release, 20f, 20f, 2750)
            assertEquals(ScreenAction(ScreenActionKind.Tap, x = 500, y = 250), sent.last().second.single())
        }
    }

    @OptIn(androidx.compose.ui.InternalComposeUiApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
    @Test fun twoFingerTrackpadMotionScrollsWithoutClicking() = javax.swing.SwingUtilities.invokeAndWait {
        val frame = ScreenFrame(ScreenRef("computer", "one"), "sub", "epoch", 1, sample(1000, 500))
        val sent = mutableListOf<ScreenAction>()
        ImageComposeScene(200, 200, Density(1f), coroutineContext = Dispatchers.Main, content = { HarnessTheme {
            LiveCanvas(frame, true, { _, actions -> sent.addAll(actions) }, {}, {}, scroll = true, trackpad = true)
        } }).use { scene ->
            awaitFrame(scene)
            fun point(id: Long, x: Float, y: Float, pressed: Boolean) = ComposeScenePointer(PointerId(id), Offset(x, y), pressed, PointerType.Touch)
            scene.sendPointerEvent(PointerEventType.Press, listOf(point(0, 50f, 50f, true)), timeMillis = 2000, buttons = PointerButtons(isPrimaryPressed = true))
            scene.sendPointerEvent(PointerEventType.Press, listOf(point(0, 50f, 50f, true), point(1, 120f, 50f, true)), timeMillis = 2050, buttons = PointerButtons(isPrimaryPressed = true))
            scene.sendPointerEvent(PointerEventType.Move, listOf(point(0, 50f, 90f, true), point(1, 120f, 90f, true)), timeMillis = 2100, buttons = PointerButtons(isPrimaryPressed = true))
            scene.sendPointerEvent(PointerEventType.Release, listOf(point(0, 50f, 90f, false), point(1, 120f, 90f, false)), timeMillis = 2200)
            repeat(5) { scene.render(2_300_000_000L + it * 100_000_000L); Thread.sleep(10) }
            assertEquals(listOf(ScreenAction(ScreenActionKind.Scroll, direction = "up")), sent)
        }
    }
    @OptIn(androidx.compose.ui.InternalComposeUiApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
    @Test fun pinchZoomPreservesSourceCoordinatesWhenSwitchingBackToTouch() = javax.swing.SwingUtilities.invokeAndWait {
        val frame = ScreenFrame(ScreenRef("browser", "one", BrowserOwner("agent")), "sub", "epoch", 1, sample(1000, 500))
        var trackpad by mutableStateOf(true)
        val sent = mutableListOf<ScreenAction>()
        ImageComposeScene(200, 200, Density(1f), coroutineContext = Dispatchers.Main, content = { HarnessTheme {
            LiveCanvas(frame, true, { _, actions -> sent.addAll(actions) }, {}, {}, trackpad = trackpad)
        } }).use { scene ->
            awaitFrame(scene)
            fun point(id: Long, x: Float, pressed: Boolean) = ComposeScenePointer(PointerId(id), Offset(x, 100f), pressed, PointerType.Touch)
            scene.sendPointerEvent(PointerEventType.Press, listOf(point(0, 50f, true)), timeMillis = 2000, buttons = PointerButtons(isPrimaryPressed = true))
            scene.sendPointerEvent(PointerEventType.Press, listOf(point(0, 50f, true), point(1, 150f, true)), timeMillis = 2050, buttons = PointerButtons(isPrimaryPressed = true))
            scene.sendPointerEvent(PointerEventType.Move, listOf(point(0, 25f, true), point(1, 175f, true)), timeMillis = 2100, buttons = PointerButtons(isPrimaryPressed = true))
            scene.sendPointerEvent(PointerEventType.Release, listOf(point(0, 25f, false), point(1, 175f, false)), timeMillis = 2200)
            assertTrue(sent.isEmpty())
            trackpad = false
            repeat(5) { scene.render(2_300_000_000L + it * 10_000_000L) }
            scene.sendPointerEvent(PointerEventType.Press, Offset(150f, 100f), timeMillis = 2500, type = PointerType.Touch)
            scene.sendPointerEvent(PointerEventType.Release, Offset(150f, 100f), timeMillis = 2550, type = PointerType.Touch)
            assertEquals(listOf(ScreenAction(ScreenActionKind.Tap, x = 667, y = 250)), sent)
        }
    }

}
