package kinuvo.ui

import kinuvo.app.session.LiveCallLevels
import kinuvo.ui.session.microphoneFailure
import kinuvo.ui.session.parseLevels
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BrowserCallTest {
    @Test
    fun deniedMicrophonePointsToTheBrowserSettings() {
        for (name in listOf("NotAllowedError", "PermissionDeniedError", "SecurityError")) {
            assertTrue(microphoneFailure(name).startsWith("Microphone access is blocked"), name)
        }
    }

    @Test
    fun missingAndBusyMicrophonesAreToldApart() {
        assertEquals("No microphone found. Connect one and try again.", microphoneFailure("NotFoundError"))
        assertTrue(microphoneFailure("NotReadableError").contains("in use"))
    }

    @Test
    fun unknownFailuresNameTheError() {
        assertEquals("Couldn't start the microphone (TypeError).", microphoneFailure("TypeError"))
        assertEquals("Couldn't start the microphone.", microphoneFailure(null))
        assertEquals("Couldn't start the microphone.", microphoneFailure(" "))
    }

    @Test
    fun levelsAreClampedAndTolerateBadStats() {
        assertEquals(LiveCallLevels(0.25f, 0.5f), parseLevels("0.25,0.5"))
        assertEquals(LiveCallLevels(1f, 0f), parseLevels("3,-1"))
        assertEquals(LiveCallLevels(0f, 0.5f), parseLevels("NaN,0.5"))
        assertEquals(LiveCallLevels(0.2f, 0f), parseLevels("0.2"))
        assertEquals(LiveCallLevels(), parseLevels(""))
    }
}
