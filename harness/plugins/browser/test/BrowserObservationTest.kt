package kinuvo.harness.browser

import kotlin.test.*

class BrowserObservationTest {
    @Test fun referencesRequireTheExactCaptureAndControlGeneration() {
        val observation = BrowserObservation()
        assertFailsWith<IllegalStateException> { observation.requireReference("first", 0) }
        observation.capture("first", 0)
        observation.requireReference("first", 0)
        assertFailsWith<IllegalArgumentException> { observation.requireReference("other", 0) }
        assertFailsWith<IllegalStateException> { observation.requireReference("first", 1) }
        observation.capture("second", 2)
        assertFailsWith<IllegalArgumentException> { observation.requireReference("first", 2) }
        observation.requireReference("second", 2)
        observation.invalidate()
        assertFailsWith<IllegalStateException> { observation.requireGeneration(2) }
    }
}
