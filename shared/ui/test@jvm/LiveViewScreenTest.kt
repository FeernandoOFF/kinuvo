package kinuvo.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.AnnotatedString
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
    private fun awaitFrame(scene: ImageComposeScene) {
        // A deadline rather than a count: the first decode can take seconds on a cold CI runner.
        val deadline = System.nanoTime() + 15_000_000_000L
        while (System.nanoTime() < deadline) {
            scene.render(System.nanoTime())
            if (scene.semanticsOwners.flatMap { nodes(it.rootSemanticsNode) }.any { "Live screen" in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }) return
            Thread.sleep(10)
        }
        fail("Frame must be decoded before input")
    }

    @Test fun desktopStatesAndOverview() = javax.swing.SwingUtilities.invokeAndWait {
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
        for ((width,height) in listOf(1200 to 850)) for (mode in listOf("watch", "held", "request", "suspended", "reconnect", "offline", "empty")) {
            val control = when(mode) { "held" -> ScreenControl(1,"device"); "suspended" -> ScreenControl(1,"other",true); else -> ScreenControl() }
            val frame = ScreenFrame(ref,"sub","epoch",1,ScreenSample(Base64.encode(bytes),1280,800,"1280:800"))
            val info = ScreenInfo(ref,"Cedar — Your account",ScreenCapabilities(ScreenDelivery.Continuous,setOf("tap","scroll","type","key"),navigation=true,tabs=true,keys=listOf("Tab", "Escape", "Enter")),ScreenStatus.Ready,control, active=mode=="watch", currentUrl="https://cedar.example/account")
            val preview = frame.copy(screen = previewRef, subscription = "preview-sub")
            val state = LiveViewState(screens=if(mode=="empty") emptyList() else listOf(info, info.copy(ref = previewRef, title = "Cedar — Plans"), info.copy(ref = missingRef, title = "Loading tab")), thumbnails=mapOf(previewRef to preview), selected=ref, connected=mode!="offline", loaded=true, device="device", subscription=ScreenSubscription("sub","epoch"),frame=if(mode=="empty") null else frame,displayed=frame,owner=owner, frameReceivedAt="2026-10-29T16:00:00Z", pending=mode=="reconnect", requestReason=if(mode=="request") "Sign in to continue" else null)
            val drawn = mutableListOf<ScreenFrame>()
            val commands = mutableListOf<ScreenCommandKind>()
            ImageComposeScene(width*2,height*2,Density(2f), content={ HarnessTheme(dark=true) { androidx.compose.runtime.CompositionLocalProvider(kinuvo.ui.navigation.LocalHomeChrome provides kinuvo.ui.navigation.HomeChrome({}, width >= 760)) { LiveViewContent(state,LiveActions(drawn = { drawn.add(it) }, command = { kind, _ -> commands.add(kind) }), ({}).takeIf { width < 760 }, docked = width < 760) } } }).use { scene ->
                repeat(15) { scene.render(it*100_000_000L); Thread.sleep(10) }
                if (mode != "empty") assertEquals(listOf(frame), drawn)
                val tree = scene.semanticsOwners.flatMap { nodes(it.rootSemanticsNode) }
                assertEquals(width < 760, tree.any { "Back" in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() })
                assertFalse(tree.any { it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { label -> label.startsWith("Toggle sidebar") } })
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
                if (mode != "empty") {
                    for (label in listOf("Go back", "Go forward")) {
                        val button = tree.first { label in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }
                        assertEquals(mode != "held", button.config.contains(SemanticsProperties.Disabled))
                        if (mode == "held") assertTrue(button.config[SemanticsActions.OnClick].action!!.invoke())
                    }
                    if (mode == "held") assertEquals(listOf(ScreenCommandKind.Back, ScreenCommandKind.Forward), commands)
                }
                if (mode != "empty") {
                    val heldTabs = tree.filter { it.config.getOrNull(SemanticsProperties.StateDescription) == "You’re in control" }
                    assertEquals(if (mode == "held") 3 else 0, heldTabs.size)
                }
                if (mode == "held") {
                    val keyboard = tree.first { it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { it == "Keyboard" } && it.config.getOrNull(SemanticsActions.OnClick) != null }
                    assertTrue(keyboard.config[SemanticsActions.OnClick].action!!.invoke())
                    repeat(5) { scene.render(3_000_000_000L + it * 100_000_000L) }
                    assertTrue(scene.semanticsOwners.flatMap { nodes(it.rootSemanticsNode) }.any { it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == "Type directly into the page…" } })
                    File("build/screens/live-$width-keyboard.png").writeBytes(scene.render(4_000_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes)
                }
            }
        }
    }
    @Test fun expandedBrowserHasNoExtraSidebarButtonAndKeepsOptionsInToolbar() = javax.swing.SwingUtilities.invokeAndWait {
        val owner = BrowserOwner("agent")
        val ref = ScreenRef("browser", "one", owner)
        val info = ScreenInfo(ref, "Example", ScreenCapabilities(ScreenDelivery.Continuous, setOf("tap")), ScreenStatus.Ready)
        val state = LiveViewState(screens = listOf(info), selected = ref, owner = owner, connected = true, presentation = BrowserPresentation())
        var shellToggles = 0
        ImageComposeScene(1200, 850, Density(1f), content = { HarnessTheme {
            androidx.compose.runtime.CompositionLocalProvider(kinuvo.ui.navigation.LocalHomeChrome provides kinuvo.ui.navigation.HomeChrome({ shellToggles++ }, true)) {
                LiveViewContent(state, LiveActions(), null)
            }
        } }).use { scene ->
            repeat(5) { scene.render(System.nanoTime()) }
            val tree = scene.semanticsOwners.flatMap { nodes(it.rootSemanticsNode) }
            assertFalse(tree.any { "Toggle chat sidebar" in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() })
            assertEquals(0, shellToggles)
            val options = tree.first { "Browser options" in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }
            assertTrue(options.boundsInRoot.top < 52f)
            assertTrue(options.boundsInRoot.width <= 44f)
            assertFalse(tree.any { it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { t -> t.text == "Browser window" } })
            File("build/screens").apply { mkdirs() }.resolve("live-expanded-toolbar.png").writeBytes(scene.render(System.nanoTime()).encodeToData(EncodedImageFormat.PNG)!!.bytes)
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
        val image = java.awt.image.BufferedImage(1000, 500, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val bytes = java.io.ByteArrayOutputStream().also { javax.imageio.ImageIO.write(image, "png", it) }.toByteArray()
        val first = ScreenFrame(ScreenRef("computer", "one"), "sub", "epoch", 1, ScreenSample(Base64.encode(bytes), 1000, 500, "first"))
        val frame = androidx.compose.runtime.mutableStateOf(first)
        val sent = mutableListOf<Pair<ScreenFrame, List<ScreenAction>>>()
        ImageComposeScene(200, 100, Density(1f), coroutineContext = kotlinx.coroutines.Dispatchers.Main, content = { HarnessTheme { LiveCanvas(frame.value, true, { f, actions -> sent.add(f to actions) }, {}, {}, Modifier.fillMaxSize()) } }).use { scene ->
            awaitFrame(scene)
            scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Press, androidx.compose.ui.geometry.Offset(20f,20f), timeMillis = 2000, type = androidx.compose.ui.input.pointer.PointerType.Touch)
            frame.value = first.copy(sequence = 2, sample = first.sample.copy(width = 2000, geometry = "second"))
            repeat(15) { awaitFrame(scene); scene.render(2_100_000_000L + it * 10_000_000L) }
            scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Release, androidx.compose.ui.geometry.Offset(20f,20f), timeMillis = 2400, type = androidx.compose.ui.input.pointer.PointerType.Touch)
            scene.render(2_500_000_000L)
            assertEquals(listOf(first to listOf(ScreenAction(ScreenActionKind.Tap, x = 100, y = 100))), sent)
        }
    }
    @Test fun gestureCannotCrossScreenEpochOrControlGeneration() = javax.swing.SwingUtilities.invokeAndWait {
        val image = java.awt.image.BufferedImage(1000, 500, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val bytes = java.io.ByteArrayOutputStream().also { javax.imageio.ImageIO.write(image, "png", it) }.toByteArray()
        val first = ScreenFrame(ScreenRef("computer", "one"), "sub", "epoch", 1, ScreenSample(Base64.encode(bytes), 1000, 500, "geometry"))
        for (change in listOf("screen", "epoch", "generation", "interactive")) {
            val frame = androidx.compose.runtime.mutableStateOf(first)
            val generation = androidx.compose.runtime.mutableStateOf(1L)
            val interactive = androidx.compose.runtime.mutableStateOf(true)
            val sent = mutableListOf<ScreenFrame>()
            ImageComposeScene(200, 100, Density(1f), coroutineContext = kotlinx.coroutines.Dispatchers.Main, content = { HarnessTheme {
                LiveCanvas(frame.value, interactive.value, { f, _ -> sent.add(f) }, {}, {}, Modifier.fillMaxSize(), generation = generation.value)
            } }).use { scene ->
                awaitFrame(scene)
                scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Press, androidx.compose.ui.geometry.Offset(20f,20f), timeMillis = 2000, type = androidx.compose.ui.input.pointer.PointerType.Touch)
                when (change) {
                    "screen" -> frame.value = first.copy(screen = first.screen.copy(id = "two"))
                    "epoch" -> frame.value = first.copy(epoch = "next")
                    "generation" -> generation.value++
                    else -> interactive.value = false
                }
                repeat(15) { awaitFrame(scene); scene.render(2_100_000_000L + it * 10_000_000L) }
                scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Release, androidx.compose.ui.geometry.Offset(20f,20f), timeMillis = 2400, type = androidx.compose.ui.input.pointer.PointerType.Touch)
                scene.render(2_500_000_000L)
                assertTrue(sent.isEmpty(), "Gesture crossed $change")
            }
        }
    }
    @Test fun controlledBrowserResizesToThePanelAndHighlightsOnlyLocalHolds() = javax.swing.SwingUtilities.invokeAndWait {
        fun image(width: Int, height: Int): String {
            val bitmap = java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_RGB)
            bitmap.createGraphics().apply {
                color = java.awt.Color(238, 244, 243); fillRect(0, 0, width, height)
                color = java.awt.Color(14, 77, 74); fillRect(0, 0, width, 42)
                color = java.awt.Color.WHITE; drawString("Cedar — Your account", 16, 26)
                color = java.awt.Color.WHITE; fillRoundRect(20, 80, (width - 40).coerceAtMost(360), 200, 12, 12)
                color = java.awt.Color(14, 77, 74); drawString("Sign in to continue", 40, 120)
                color = java.awt.Color(210, 222, 220); fillRect(40, 146, (width - 80).coerceAtMost(320), 34)
                color = java.awt.Color(14, 77, 74); fillRect(40, 204, (width - 80).coerceAtMost(320), 34)
                color = java.awt.Color.WHITE; drawString("Continue", 60, 226); dispose()
            }
            return Base64.encode(java.io.ByteArrayOutputStream().also { javax.imageio.ImageIO.write(bitmap, "png", it) }.toByteArray())
        }
        for ((width, height) in listOf(390 to 844, 520 to 1100)) {
            val owner = BrowserOwner("agent")
            val ref = ScreenRef("browser", "one", owner)
            val frame = ScreenFrame(ref, "sub", "epoch", 1, ScreenSample(image(1280, 800), 1280, 800, "initial"))
            val info = ScreenInfo(ref, "Cedar — Your account", ScreenCapabilities(ScreenDelivery.Continuous, setOf("tap", "type", "key"),
                navigation = true, tabs = true, keys = BrowserKeys, modifiers = BrowserModifiers, resize = true), ScreenStatus.Ready, ScreenControl(1, "device"), currentUrl = "https://cedar.example/account")
            val state = androidx.compose.runtime.mutableStateOf(LiveViewState(screens = listOf(info, info.copy(ref = ref.copy(id = "two"), title = "Cedar — Plans", control = ScreenControl(1, "other"))),
                selected = ref, connected = true, device = "device", owner = owner, frame = frame, displayed = frame))
            val sizes = mutableListOf<Pair<Int, Int>>()
            ImageComposeScene(width, height, Density(1f), content = { HarnessTheme(dark = true) {
                LiveViewContent(state.value, LiveActions(resize = { w, h ->
                    sizes.add(w to h)
                    val next = frame.copy(sequence = 2, sample = ScreenSample(image(w, h), w, h, "$w:$h"))
                    state.value = state.value.copy(frame = next, displayed = next)
                }), null, docked = true)
            } }).use { scene ->
                repeat(30) { scene.render(it * 100_000_000L); Thread.sleep(15) }
                assertEquals(1, sizes.size)
                val tree = scene.semanticsOwners.flatMap { nodes(it.rootSemanticsNode) }
                assertEquals(1, tree.count { it.config.getOrNull(SemanticsProperties.StateDescription) == "You’re in control" })
                val screen = tree.first { "Live screen" in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }
                assertEquals(sizes.single().first.toFloat(), screen.boundsInRoot.width, 1f)
                assertEquals(sizes.single().second.toFloat(), screen.boundsInRoot.height, 1f)
                File("build/screens/live-responsive-$width.png").writeBytes(scene.render(4_000_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes)
            }
        }
    }

    @OptIn(androidx.compose.ui.InternalComposeUiApi::class)
    @Test fun keyboardForwardsTextAndEditingKeysOnlyWhileInControl() = javax.swing.SwingUtilities.invokeAndWait {
        val ref = ScreenRef("browser", "keyboard", BrowserOwner("agent"))
        val frame = ScreenFrame(ref, "sub", "epoch", 1, ScreenSample("", 1280, 800, "geometry"))
        val capabilities = ScreenCapabilities(ScreenDelivery.Continuous, setOf("type", "key"), keys = BrowserKeys, modifiers = BrowserModifiers)
        val info = ScreenInfo(ref, "Keyboard", capabilities, ScreenStatus.Ready, ScreenControl(1, "device"))
        val state = androidx.compose.runtime.mutableStateOf(LiveViewState(screens = listOf(info), selected = ref, connected = true, device = "device", displayed = frame))
        val sent = mutableListOf<ScreenAction>()
        val focus = FocusRequester()
        ImageComposeScene(400, 120, Density(1f), content = { HarnessTheme {
            RemoteKeyboard(state.value, LiveActions(input = { _, actions -> sent.addAll(actions) }), focus, true)
        } }).use { scene ->
            repeat(10) { scene.render(it * 100_000_000L) }
            val field = scene.semanticsOwners.flatMap { nodes(it.rootSemanticsNode) }.first { it.config.getOrNull(SemanticsActions.SetText) != null }
            assertTrue(field.config[SemanticsActions.SetText].action!!.invoke(AnnotatedString("Hello 👋")))
            scene.render(1_100_000_000L)
            assertEquals(listOf(ScreenAction(ScreenActionKind.Type, text = "Hello 👋")), sent)
            field.config[SemanticsActions.RequestFocus].action!!.invoke()
            val native = java.awt.event.KeyEvent(java.awt.Canvas(), java.awt.event.KeyEvent.KEY_TYPED, 0, 0, java.awt.event.KeyEvent.VK_UNDEFINED, 'x')
            val typed = KeyEvent(Key.X, KeyEventType.KeyDown, codePoint = 'x'.code, nativeEvent = native)
            scene.sendKeyEvent(typed)
            scene.render(1_150_000_000L)
            assertEquals(ScreenAction(ScreenActionKind.Type, text = "x"), sent.last())
            val tab = KeyEvent(Key.Tab, KeyEventType.KeyDown)
            assertTrue(scene.sendKeyEvent(tab))
            assertEquals(ScreenAction(ScreenActionKind.Key, key = "Tab"), sent.last())
            val select = KeyEvent(Key.A, KeyEventType.KeyDown, isCtrlPressed = true)
            assertTrue(scene.sendKeyEvent(select))
            assertEquals(ScreenAction(ScreenActionKind.Key, key = "Control+A"), sent.last())
            assertNull(remoteKey(KeyEvent(Key.A, KeyEventType.KeyDown, isCtrlPressed = true, isAltPressed = true), capabilities))
            state.value = state.value.copy(connected = false)
            scene.render(1_200_000_000L)
            scene.sendKeyEvent(tab)
            assertEquals(4, sent.size)
        }
    }

    private fun nodes(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::nodes)
}
