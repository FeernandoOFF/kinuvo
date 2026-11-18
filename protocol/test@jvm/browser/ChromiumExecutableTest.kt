package kinuvo.protocol.browser

import kinuvo.protocol.device.Platform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ChromiumExecutableTest {
    @Test fun windowsInstallLocations() {
        for (root in listOf("ProgramFiles", "ProgramFiles(x86)", "LOCALAPPDATA")) {
            for (browser in listOf("Google\\Chrome\\Application\\chrome.exe", "Microsoft\\Edge\\Application\\msedge.exe",
                "BraveSoftware\\Brave-Browser\\Application\\brave.exe", "Chromium\\Application\\chrome.exe")) {
                val path = "C:\\Apps\\$browser"
                assertEquals(path, findChromium(mapOf(root to "C:\\Apps"), Platform.Windows, emptyList(), { it == path }))
            }
        }
    }

    @Test fun windowsPath() {
        val path = "C:\\bin\\msedge.exe"
        assertEquals(path, findChromium(emptyMap(), Platform.Windows, listOf("C:\\bin"), { it == path }))
    }

    @Test fun linuxCommands() {
        for (name in listOf("google-chrome", "google-chrome-stable", "chromium", "chromium-browser",
            "microsoft-edge", "microsoft-edge-stable", "brave-browser")) {
            val path = "/usr/bin/$name"
            assertEquals(path, findChromium(emptyMap(), Platform.Linux, listOf("/usr/bin"), { it == path }))
        }
        assertEquals("/snap/bin/chromium", findChromium(emptyMap(), Platform.Linux, emptyList(), { it == "/snap/bin/chromium" }))
    }

    @Test fun macApplications() {
        for (app in listOf("Google Chrome", "Chromium", "Microsoft Edge", "Brave Browser")) {
            val path = "/Applications/$app.app/Contents/MacOS/$app"
            assertEquals(path, findChromium(emptyMap(), Platform.MacOS, emptyList(), { it == path }))
        }
    }

    @Test fun overrideWinsAndMustBeExecutable() {
        assertEquals("/custom", findChromium(mapOf("KINUVO_BROWSER_EXECUTABLE" to "/custom"),
            Platform.Linux, listOf("/usr/bin"), { true }, { error("unused") }))
        assertFailsWith<IllegalArgumentException> {
            findChromium(mapOf("KINUVO_BROWSER_EXECUTABLE" to "/missing"), Platform.Windows, emptyList(), { false })
        }
    }

    @Test fun fallbackAndNoneFound() {
        assertEquals("/fallback", findChromium(emptyMap(), Platform.MacOS, emptyList(), { it == "/fallback" }, { "/fallback" }))
        assertNull(findChromium(emptyMap(), Platform.Linux, emptyList(), { false }, { "/missing" }))
    }

    @Test fun existingBrowserChoiceWins() {
        assertEquals("/existing/chrome", findChromium(emptyMap(), Platform.Linux,
            listOf("/usr/bin"), { true }, { "/existing/chrome" }))
    }

    @Test fun chromeBeforeSnapAndOtherPathBrowsers() {
        assertEquals("/usr/bin/google-chrome", findChromium(emptyMap(), Platform.Linux,
            listOf("/snap/bin", "/usr/bin"), { it == "/snap/bin/chromium" || it == "/usr/bin/google-chrome" }))
        assertEquals("/opt/google/chrome/chrome", findChromium(emptyMap(), Platform.Linux,
            listOf("/snap/bin"), { it == "/snap/bin/chromium" || it == "/opt/google/chrome/chrome" }))
    }

    @Test fun platformPathSeparator() {
        assertEquals("D:\\bin\\msedge.exe", findChromium(
            mapOf("PATH" to "C:\\bin;D:\\bin"), Platform.Windows,
            executable = { it == "D:\\bin\\msedge.exe" }))
    }

    @Test fun firstCandidateWins() {
        assertEquals("C:\\Apps\\Google\\Chrome\\Application\\chrome.exe",
            findChromium(mapOf("ProgramFiles" to "C:\\Apps"), Platform.Windows, listOf("C:\\bin"), { true }))
        assertEquals("/first/google-chrome",
            findChromium(emptyMap(), Platform.Linux, listOf("/first", "/second"), { it.startsWith("/first/") || it.startsWith("/second/") }))
    }
}
