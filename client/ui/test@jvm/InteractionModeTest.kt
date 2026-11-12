package kinuvo.ui

import com.composables.ui.theme.InteractionMode
import kinuvo.protocol.device.Platform
import kinuvo.ui.design.interactionMode
import kotlin.test.Test
import kotlin.test.assertEquals

class InteractionModeTest {
    @Test fun mobileKeepsTouchStylingWithMouseCapableHardware() {
        for (platform in listOf(Platform.Android, Platform.IOS)) {
            for (hasPointer in listOf(false, true)) {
                assertEquals(InteractionMode.Touch, interactionMode(platform, hasPointer), "$platform, pointer=$hasPointer")
            }
        }
    }

    @Test fun desktopKeepsPointerStylingWithoutAMouse() {
        for (platform in listOf(Platform.MacOS, Platform.Windows, Platform.Linux)) {
            for (hasPointer in listOf(false, true)) {
                assertEquals(InteractionMode.Pointer, interactionMode(platform, hasPointer), "$platform, pointer=$hasPointer")
            }
        }
    }

    @Test fun webKeepsCapabilityBasedStyling() {
        assertEquals(InteractionMode.Touch, interactionMode(Platform.Web, hasPointer = false))
        assertEquals(InteractionMode.Pointer, interactionMode(Platform.Web, hasPointer = true))
    }
}
