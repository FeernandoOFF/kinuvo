package kinuvo.ui

import kinuvo.app.navigation.*
import kinuvo.app.settings.*
import kinuvo.ui.settings.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import kotlinx.coroutines.Dispatchers
import kinuvo.protocol.agent.*
import kinuvo.ui.design.KinuvoTheme
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test

/** Open source licenses, rendered to `build/screens/licenses-*.png`. */
class LicensesScreenshotTest {
    private val out = File("build/screens").apply { mkdirs() }
    private val agents = listOf(AgentInfo(AgentId("main"), "Kinuvo", "host"))

    private fun settings(item: String?) = SettingsUi(SettingsState(Route.Settings(AgentId("main"), SettingsSection.Licenses, item)), agents)

    @Test fun renderLicenses() {
        render("licenses-list-desktop", 1200, 760) { SettingsContent(settings(null), SettingsActions()) }
        render("licenses-detail-phone", 390, 844) { SettingsContent(settings("PRoot"), SettingsActions(), sidebar = false) }
    }

    private fun render(name: String, width: Int, height: Int, content: @Composable () -> Unit) = javax.swing.SwingUtilities.invokeAndWait {
        ImageComposeScene(width * 2, height * 2, Density(2f), coroutineContext = Dispatchers.Main, content = { KinuvoTheme(dark = false) { content() } }).use { scene ->
            repeat(5) { scene.render(it * 500_000_000L) }
            File(out, "$name.png").writeBytes(scene.render(3_000_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes)
        }
    }
}
