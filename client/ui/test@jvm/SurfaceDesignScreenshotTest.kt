package kinuvo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import com.composables.ui.components.*
import com.composables.ui.theme.backgroundColor
import com.composables.icons.lucide.*
import kinuvo.ui.design.*
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test

class SurfaceDesignScreenshotTest {
    @Test fun controls() = javax.swing.SwingUtilities.invokeAndWait {
        for (dark in listOf(false, true)) ImageComposeScene(1000, 2300, Density(2f), content = {
            HarnessTheme(dark = dark) {
                Column(Modifier.fillMaxSize().background(color(backgroundColor)).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("Surface design kit", style = TextStyles.title)
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        listOf("favorite", "calendarToday", "play", "warning", "unknown").forEach { Icon(catalogIcon(it), Modifier.size(24.dp), contentDescription = it) }
                    }
                    val picture = remember { Image.makeFromEncoded(File("composeResources/files/avatar-lilac-cloud-working.png").readBytes()).toComposeImageBitmap() }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Picture(picture, "Avatar", variant = "avatar", fit = "cover")
                        Picture(picture, "Feature", variant = "smallFeature", fit = "contain")
                        Picture(null, "Image unavailable", variant = "smallFeature")
                    }
                    Slider(50.0, {}, 0.0, 100.0, 4, "Progress")
                    DateTimeInput("2026-11-06", {}, true, false, label = "Date")
                    DateTimeInput("14:30", {}, false, true, label = "Time")
                    DateTimeInput("2026-11-06T14:30", {}, true, true, label = "Date and time")
                    DateTimeInput("2026-02-30", {}, true, false, label = "Invalid date")
                    Tabs(listOf("Overview", "Details"), 0, {}) { Text("Selected tab content") }
                    MediaCard("Video", "A guided walkthrough", {})
                    MediaCard("Audio", "A moment of calm", {})
                    Modal(trigger = { Button(onClick = {}) { Text("Review details") } }) { Text("Modal content") }
                }
            }
        }).use { scene ->
            repeat(8) { scene.render(it * 200_000_000L) }
            File("build/screens").apply { mkdirs() }.resolve("surface-design-${if (dark) "dark" else "light"}.png")
                .writeBytes(scene.render(2_000_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes)
        }
    }

    @Test fun modalContent() = javax.swing.SwingUtilities.invokeAndWait {
        for (dark in listOf(false, true)) ImageComposeScene(1000, 800, Density(2f), content = {
            HarnessTheme(dark = dark) {
                val popovers = remember { Popovers() }
                CompositionLocalProvider(LocalPopovers provides popovers) {
                    Box(Modifier.fillMaxSize().background(color(backgroundColor))) {
                        Text("Your Surface", Modifier.padding(24.dp))
                        Popover(true, {}) {
                            Text("Review your plan", style = TextStyles.title)
                            Text("Make time for what matters.")
                            Button(onClick = {}, style = ButtonStyle.Ghost) { Text("Close") }
                        }
                        popovers.Host()
                    }
                }
            }
        }).use { scene ->
            repeat(8) { scene.render(it * 200_000_000L) }
            File("build/screens").apply { mkdirs() }.resolve("surface-modal-${if (dark) "dark" else "light"}.png")
                .writeBytes(scene.render(2_000_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes)
        }
    }
}
