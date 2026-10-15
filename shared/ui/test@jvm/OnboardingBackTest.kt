package kinuvo.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import kinuvo.app.agent.AgentSteps
import kinuvo.app.agent.OnboardingState
import kinuvo.ui.agent.OnboardingContent
import kinuvo.ui.design.HarnessTheme
import kinuvo.ui.design.rememberBackDispatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OnboardingBackTest {
    @Test fun backStepsBackThenCloses() = javax.swing.SwingUtilities.invokeAndWait {
        val state = mutableStateOf(OnboardingState(step = 5, steps = AgentSteps))
        var closed = false
        var back: () -> Boolean = { false }
        ImageComposeScene(390, 844, Density(1f), content = {
            back = rememberBackDispatch()
            HarnessTheme(dark = false) { OnboardingContent(state.value, onEdit = { state.value = it(state.value) }, onClose = { closed = true }) }
        }).use { scene ->
            var time = 0L
            fun frame() = repeat(3) { scene.render(time.also { time += 100_000_000 }) }
            frame()
            assertTrue(back())
            frame()
            assertEquals(4, state.value.step)
            assertFalse(closed)
            assertTrue(back())
            frame()
            assertTrue(closed)
            state.value = state.value.copy(busy = true)
            frame()
            assertFalse(back(), "No back while saving")
        }
    }
}
