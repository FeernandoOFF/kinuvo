package kinuvo.ui.e2e

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.setMain
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import java.io.File

private const val SCALE = 2f

/** Nucleus Tao claims Dispatchers.Main, which only runs inside its application; tests keep Swing's, as before. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
private val swingMain by lazy { Dispatchers.setMain(Dispatchers.Swing) }

/** Runs [block] on a 390×844dp phone screen. */
@OptIn(ExperimentalTestApi::class)
internal fun runPhoneTest(block: suspend ComposeUiTest.() -> Unit): TestResult =
    swingMain.let { _ -> runSkikoComposeUiTest(Size(390 * SCALE, 844 * SCALE), Density(SCALE)) { block() } }

/** Runs [block] on a 1440×900dp desktop window. */
@OptIn(ExperimentalTestApi::class)
internal fun runDesktopTest(block: suspend ComposeUiTest.() -> Unit): TestResult =
    swingMain.let { _ -> runSkikoComposeUiTest(Size(1440 * SCALE, 900 * SCALE), Density(SCALE)) { block() } }

/** Saves the screen to `build/screens/flows/<name>.png`. */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.saveScreenshot(name: String) {
    val png = Image.makeFromBitmap((this as SkikoComposeUiTest).captureToImage().asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG)!!.bytes
    File("build/screens/flows/$name.png").apply { parentFile.mkdirs() }.writeBytes(png)
}
