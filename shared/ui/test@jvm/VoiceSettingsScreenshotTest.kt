package kinuvo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import com.composables.ui.theme.backgroundColor
import kinuvo.app.voice.*
import kinuvo.ui.design.*
import kinuvo.ui.voice.VoiceSettingsContent
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.*

class VoiceSettingsScreenshotTest {
    @Test fun voiceSettingsRenderInstalledAndDownloadingModelsAtBothWidths() = javax.swing.SwingUtilities.invokeAndWait {
        val models = listOf(
            model("whisper-tiny", "Whisper Tiny", VoiceModelKind.Transcription, 103_609_903),
            model("whisper-base", "Whisper Base", VoiceModelKind.Transcription, 160_609_290),
            model("supertonic-3", "Supertonic 3", VoiceModelKind.Synthesis, 128_774_318),
        )
        val state = LocalVoiceState(models, setOf("whisper-tiny", "supertonic-3"), "whisper-tiny", "supertonic-3")
        for ((width, height) in listOf(780 to 900, 390 to 1080)) for (dark in listOf(true, false)) for (downloading in listOf(false, true)) {
            val shown = if (downloading) state.copy(downloading = "whisper-base", downloaded = 67_000_000) else state
            ImageComposeScene(width * 2, height * 2, Density(2f), content = {
                HarnessTheme(dark = dark) {
                    Column(Modifier.fillMaxSize().background(color(backgroundColor)).verticalScroll(rememberScrollState()).padding(24.dp)) {
                        VoiceSettingsContent(shown, {}, {}, {}, { _, _ -> }, {})
                    }
                }
            }).use { scene ->
                repeat(12) { scene.render(it * 100_000_000L) }
                val texts = scene.semanticsOwners.flatMap { text(it.rootSemanticsNode) }
                assertTrue("Dictation model" in texts)
                assertTrue("Spoken replies" in texts)
                assertTrue("Device-local voice" !in texts)
                assertEquals(downloading, "Cancel" in texts)
                val suffix = "${width}-${if (dark) "dark" else "light"}-${if (downloading) "downloading" else "ready"}"
                File("build/screens").apply { mkdirs() }.resolve("voice-settings-$suffix.png")
                    .writeBytes(scene.render(2_000_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes)
            }
        }
    }
    private fun model(id: String, title: String, kind: VoiceModelKind, bytes: Long) = VoiceModel(id, title, kind,
        if (kind == VoiceModelKind.Synthesis) "OpenRAIL-M" else "MIT", "https://example.org/model", "Multilingual",
        listOf(VoiceModelFile("model", "https://example.org/model", bytes, "hash")))
    private fun text(node: SemanticsNode): List<String> = node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } + node.children.flatMap(::text)
}
