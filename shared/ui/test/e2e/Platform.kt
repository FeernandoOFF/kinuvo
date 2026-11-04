package kinuvo.ui.e2e

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import kotlinx.coroutines.test.TestResult

/** Runs [block] on a 390×844dp phone screen; flows run on JVM only for now, other platforms skip them. */
@OptIn(ExperimentalTestApi::class)
internal expect fun runPhoneTest(block: suspend ComposeUiTest.() -> Unit): TestResult

/** Reads a Mockstro WireMock fixture, relative to `.maestro/wiremock/`. */
internal expect fun fixture(path: String): String

/** Saves the screen to `build/screens/flows/<name>.png` where the platform can write files. */
@OptIn(ExperimentalTestApi::class)
internal expect fun ComposeUiTest.saveScreenshot(name: String)
