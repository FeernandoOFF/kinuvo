package kinuvo.ui

import kinuvo.ui.agent.*
import kinuvo.ui.navigation.*

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import kinuvo.ui.design.HarnessTheme
import kinuvo.ui.design.Reveal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RevealGestureTest {
    @Test
    fun slowEdgeSwipeFollowsTheFingerAndOpens() = javax.swing.SwingUtilities.invokeAndWait {
        GestureScene().use {
            it.event(PointerEventType.Press, 1f)
            it.event(PointerEventType.Move, 30f)
            it.event(PointerEventType.Move, 60f)
            it.event(PointerEventType.Move, 200f, 1100)
            assertEquals(199f, it.contentX, 1f)
            it.event(PointerEventType.Release, 200f, 500)
            assertTrue(it.open.value)
            assertEquals(listOf(HapticFeedbackType.SegmentFrequentTick), it.haptics)
        }
    }

    @Test
    fun shortPeekReturnsToTheChat() = javax.swing.SwingUtilities.invokeAndWait {
        GestureScene().use {
            it.event(PointerEventType.Press, 1f)
            it.event(PointerEventType.Move, 60f, 1000)
            assertEquals(59f, it.contentX, 1f)
            it.event(PointerEventType.Release, 60f, 500)
            it.frame(1000)
            assertFalse(it.open.value)
            assertEquals(0f, it.contentX, 1f)
            assertTrue(it.haptics.isEmpty())
        }
    }

    @Test
    fun chatStripCanBeDraggedClosed() = javax.swing.SwingUtilities.invokeAndWait {
        GestureScene(initiallyOpen = true).use {
            assertEquals(342f, it.contentX, 1f)
            it.event(PointerEventType.Press, 375f)
            it.event(PointerEventType.Move, 330f)
            it.event(PointerEventType.Move, 100f, 1000)
            it.event(PointerEventType.Release, 100f, 500)
            it.frame(1000)
            assertFalse(it.open.value)
            assertEquals(0f, it.contentX, 1f)
            assertEquals(listOf(HapticFeedbackType.SegmentFrequentTick), it.haptics)
        }
    }

    @Test
    fun sidebarStateChangesTickOnceEach() = javax.swing.SwingUtilities.invokeAndWait {
        GestureScene().use {
            assertTrue(it.haptics.isEmpty())
            it.open.value = true
            it.frame(1000)
            it.frame(1000)
            assertEquals(listOf(HapticFeedbackType.SegmentFrequentTick), it.haptics)
            it.open.value = false
            it.frame(1000)
            it.frame(1000)
            assertEquals(List(2) { HapticFeedbackType.SegmentFrequentTick }, it.haptics)
        }
    }

    @Test
    fun touchingAnOpeningAnimationPausesItAndAllowsReversal() = javax.swing.SwingUtilities.invokeAndWait {
        GestureScene().use {
            it.event(PointerEventType.Press, 1f)
            it.event(PointerEventType.Move, 240f, 1000)
            it.event(PointerEventType.Release, 240f, 500)
            assertTrue(it.open.value)
            it.event(PointerEventType.Press, 375f, 10)
            val paused = it.contentX
            it.frame(200)
            assertEquals(paused, it.contentX, 1f)
            it.event(PointerEventType.Move, 300f)
            it.event(PointerEventType.Move, 90f, 1000)
            it.event(PointerEventType.Release, 90f, 500)
            it.frame(1000)
            assertFalse(it.open.value)
            assertEquals(0f, it.contentX, 1f)
        }
    }

    @Test
    fun shortFastFlickOpens() = javax.swing.SwingUtilities.invokeAndWait {
        GestureScene().use {
            it.event(PointerEventType.Press, 1f)
            it.event(PointerEventType.Move, 30f, 20)
            it.event(PointerEventType.Move, 55f, 20)
            it.event(PointerEventType.Move, 80f, 20)
            it.event(PointerEventType.Release, 80f, 5)
            assertTrue(it.open.value)
        }
    }

    @Test
    fun mediumSpeedSwipeOpensBeforeHalfway() = javax.swing.SwingUtilities.invokeAndWait {
        GestureScene().use {
            it.event(PointerEventType.Press, 1f)
            for (x in listOf(11f, 21f, 31f, 41f, 51f, 61f, 71f, 81f)) it.event(PointerEventType.Move, x, 30)
            it.event(PointerEventType.Release, 81f, 5)
            assertTrue(it.open.value)
            it.frame(1000)
            assertEquals(342f, it.contentX, 1f)
        }
    }

    @Test
    fun mediumSpeedSwipeClosesBeforeHalfway() = javax.swing.SwingUtilities.invokeAndWait {
        GestureScene(initiallyOpen = true).use {
            it.event(PointerEventType.Press, 375f)
            for (x in listOf(365f, 355f, 345f, 335f, 325f, 315f, 305f, 295f)) it.event(PointerEventType.Move, x, 30)
            it.event(PointerEventType.Release, 295f, 5)
            assertFalse(it.open.value)
            it.frame(1000)
            assertEquals(0f, it.contentX, 1f)
        }
    }

    @Test
    fun verticalEdgeSwipeStillScrollsTheChat() = javax.swing.SwingUtilities.invokeAndWait {
        val scroll = ScrollState(0)
        GestureScene(scroll = scroll, vertical = true).use {
            it.event(PointerEventType.Press, 1f, y = 500f)
            it.event(PointerEventType.Move, 1f, y = 400f)
            it.event(PointerEventType.Move, 1f, y = 200f)
            it.event(PointerEventType.Release, 1f, 500, y = 200f)
            assertFalse(it.open.value)
            assertEquals(0f, it.contentX, 1f)
            assertTrue(scroll.value > 0)
        }
    }

    @Test
    fun sidewaysContentKeepsCenterSwipes() = javax.swing.SwingUtilities.invokeAndWait {
        val scroll = ScrollState(200)
        GestureScene(scroll = scroll).use {
            it.event(PointerEventType.Press, 160f)
            it.event(PointerEventType.Move, 200f)
            it.event(PointerEventType.Move, 300f)
            it.event(PointerEventType.Release, 300f, 500)
            assertFalse(it.open.value)
            assertEquals(0f, it.contentX, 1f)
            assertTrue(scroll.value < 200)
        }
    }

    @Test
    fun edgeSwipesRevealEvenOverSidewaysContent() = javax.swing.SwingUtilities.invokeAndWait {
        val scroll = ScrollState(200)
        GestureScene(scroll = scroll).use {
            it.event(PointerEventType.Press, 1f)
            it.event(PointerEventType.Move, 30f)
            it.event(PointerEventType.Move, 200f, 1100)
            it.event(PointerEventType.Release, 200f, 500)
            assertTrue(it.open.value)
            assertEquals(200, scroll.value)
        }
    }

    private class GestureScene(initiallyOpen: Boolean = false, scroll: ScrollState? = null, vertical: Boolean = false) : AutoCloseable {
        val open = mutableStateOf(initiallyOpen)
        val haptics = mutableListOf<HapticFeedbackType>()
        private val feedback = object : HapticFeedback {
            override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) { haptics += hapticFeedbackType }
        }
        var contentX = 0f
        private var time = 0L
        private val scene = ImageComposeScene(390, 844, Density(1f), content = {
            CompositionLocalProvider(LocalHapticFeedback provides feedback) {
                HarnessTheme(dark = false) {
                    Reveal(open.value, { open.value = it }, behind = { Box(Modifier.fillMaxSize()) }) {
                        Box(Modifier.fillMaxSize().onGloballyPositioned { contentX = it.positionInRoot().x }) {
                            if (scroll != null) Box(Modifier.fillMaxSize().then(if (vertical) Modifier.verticalScroll(scroll) else Modifier.horizontalScroll(scroll))) {
                                Box(if (vertical) Modifier.height(2000.dp).fillMaxWidth() else Modifier.width(1000.dp).fillMaxSize())
                            }
                        }
                    }
                }
            }
        })

        init { scene.render(0) }

        fun event(type: PointerEventType, x: Float, delay: Long = 200, y: Float = 300f) {
            time += delay
            scene.sendPointerEvent(type, Offset(x, y), timeMillis = time, type = PointerType.Touch)
            scene.render(time * 1_000_000)
        }

        fun frame(delay: Long) {
            time += delay
            scene.render(time * 1_000_000)
        }

        override fun close() = scene.close()
    }
}
