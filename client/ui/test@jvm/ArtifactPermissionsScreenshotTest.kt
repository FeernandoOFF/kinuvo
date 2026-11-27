package kinuvo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import com.composables.ui.components.Text
import com.composables.ui.theme.InteractionMode
import com.composables.ui.theme.LocalInteractionMode
import com.composables.ui.theme.backgroundColor
import kinuvo.protocol.artifact.ArtifactCapabilities
import kinuvo.protocol.artifact.ArtifactPermissions
import kinuvo.ui.artifacts.ArtifactPermissionsPanel
import kinuvo.ui.artifacts.PermissionActions
import kinuvo.ui.artifacts.permissionsTitle
import kinuvo.ui.design.KinuvoTheme
import kinuvo.ui.design.Spacing
import kinuvo.ui.design.TextStyles
import kinuvo.ui.design.color
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** The Permissions sheet of an Artifact, rendered to `build/screens/artifact-permissions-*.png`. */
class ArtifactPermissionsScreenshotTest {
    private val out = File("build/screens").apply { mkdirs() }
    private val now = 1_790_000_600_000L
    private val asks = ArtifactCapabilities(
        network = listOf("https://socket.example.com", "wss://socket.example.com", "wss://*.rt.example.com:8443"), popups = true, storage = true, tools = listOf("search"),
    )
    private val pending = ArtifactPermissions("deck-1a2b", requested = asks)
    private val partly = ArtifactPermissions(
        "deck-1a2b", asks, granted = asks.copy(popups = false, storage = false, tools = emptyList(), network = asks.network.take(2)),
        effective = asks.copy(popups = false, storage = false, tools = emptyList(), network = asks.network.take(2)), at = now - 3_600_000, by = "phone", byName = "Pixel 9",
    )
    private val everything = ArtifactPermissions("deck-1a2b", asks, asks, asks, now - 86_400_000, "mac", "MacBook")

    @Test fun renderPermissions() {
        render("artifact-permissions-phone", 390, 820, dark = true, touch = true) { sheet(pending, true) }
        render("artifact-permissions-phone-light", 390, 820, dark = false, touch = true) { sheet(partly, true) }
        render("artifact-permissions-desktop", 460, 760, dark = false, touch = false) { sheet(everything, true) }
        render("artifact-permissions-read-only", 460, 760, dark = true, touch = false) { sheet(partly, false) }
        render("artifact-permissions-popups-only", 460, 380, dark = false, touch = false) {
            sheet(ArtifactPermissions("deck-1a2b", ArtifactCapabilities(popups = true)), true)
        }
        assertTrue(File(out, "artifact-permissions-phone.png").length() > 0)
    }

    @Composable
    private fun sheet(permissions: ArtifactPermissions, canGrant: Boolean) {
        Column(Modifier.fillMaxSize().background(color(backgroundColor)).padding(Spacing.space16)) {
            Text(permissionsTitle(permissions), Modifier.padding(bottom = Spacing.space12), style = TextStyles.title, fontWeight = FontWeight.SemiBold)
            ArtifactPermissionsPanel(permissions, canGrant, now, PermissionActions())
        }
    }

    private fun render(name: String, width: Int, height: Int, dark: Boolean, touch: Boolean, content: @Composable () -> Unit) = javax.swing.SwingUtilities.invokeAndWait {
        ImageComposeScene(width * 2, height * 2, Density(2f), coroutineContext = Dispatchers.Main, content = {
            KinuvoTheme(dark = dark) { CompositionLocalProvider(LocalInteractionMode provides if (touch) InteractionMode.Touch else InteractionMode.Pointer) { content() } }
        }).use { scene ->
            repeat(5) { scene.render(it * 500_000_000L) }
            File(out, "$name.png").writeBytes(scene.render(3_000_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes)
        }
    }
}
