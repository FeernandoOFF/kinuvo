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

@OptIn(ExperimentalTestApi::class)
internal actual fun runPhoneTest(block: suspend ComposeUiTest.() -> Unit): TestResult =
    runSkikoComposeUiTest(Size(390 * SCALE, 844 * SCALE), Density(SCALE)) { block() }

private val wiremock: File by lazy {
    generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, ".maestro/wiremock") }.first { it.isDirectory }
}

internal actual fun fixture(path: String) = File(wiremock, path).readText()

@OptIn(ExperimentalTestApi::class)
internal actual fun ComposeUiTest.saveScreenshot(name: String) {
    val png = Image.makeFromBitmap((this as SkikoComposeUiTest).captureToImage().asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG)!!.bytes
    File("build/screens/flows/$name.png").apply { parentFile.mkdirs() }.writeBytes(png)
}
