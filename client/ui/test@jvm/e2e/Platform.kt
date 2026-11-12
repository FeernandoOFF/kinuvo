package kinuvo.ui.e2e

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.test.TestResult
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import java.io.File

private const val SCALE = 2f

/** Runs [block] on a 390×844dp phone screen. */
@OptIn(ExperimentalTestApi::class)
internal fun runPhoneTest(block: suspend ComposeUiTest.() -> Unit): TestResult =
    runSkikoComposeUiTest(Size(390 * SCALE, 844 * SCALE), Density(SCALE)) { block() }

/** Saves the screen to `build/screens/flows/<name>.png`. */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.saveScreenshot(name: String) {
    val png = Image.makeFromBitmap((this as SkikoComposeUiTest).captureToImage().asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG)!!.bytes
    File("build/screens/flows/$name.png").apply { parentFile.mkdirs() }.writeBytes(png)
}
