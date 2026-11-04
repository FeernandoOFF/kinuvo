package kinuvo.ui.e2e

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalTestApi::class)
internal actual fun runPhoneTest(block: suspend ComposeUiTest.() -> Unit): TestResult = runTest {}

internal actual fun fixture(path: String): String = error("Fixtures load on JVM only")

@OptIn(ExperimentalTestApi::class)
internal actual fun ComposeUiTest.saveScreenshot(name: String) {}
