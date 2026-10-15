package kinuvo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import com.composables.icons.lucide.CircleX
import com.composables.icons.lucide.Lucide
import com.composables.ui.theme.backgroundColor
import kinuvo.app.agent.OnboardingState
import kinuvo.protocol.DeviceCode
import kinuvo.ui.agent.ModelStep
import kinuvo.ui.design.*
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.*

@Suppress("DEPRECATION")
class FeedbackTest {
    @Test fun signInCodeCanBeCopied() = javax.swing.SwingUtilities.invokeAndWait {
        var copied: AnnotatedString? = null
        val clipboard = object : ClipboardManager {
            override fun getText() = copied
            override fun setText(annotatedString: AnnotatedString) { copied = annotatedString }
        }
        val code = DeviceCode("https://example.com/sign-in", "ABCD-EFGH", 0)
        ImageComposeScene(390, 844, Density(1f), content = {
            CompositionLocalProvider(LocalClipboardManager provides clipboard) {
                HarnessTheme(dark = false) {
                    Column(Modifier.fillMaxSize().background(color(backgroundColor)).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        ModelStep(OnboardingState(deviceCode = code), {}, {}, {})
                    }
                }
            }
        }).use { scene ->
            scene.settle()
            var target = scene.nodes().first { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Copy") == true }
            while (target.config.getOrNull(SemanticsActions.OnClick) == null) target = requireNotNull(target.parent)
            target.config[SemanticsActions.OnClick].action!!.invoke()
            assertEquals(code.code, copied?.text)
            scene.screenshot("onboarding-copy-code")
        }
    }

    @Test fun longErrorPillsAndBannersKeepEveryLineVisible() = javax.swing.SwingUtilities.invokeAndWait {
        val error = "Couldn't connect to the integration.\nCheck the Server address and credentials in Settings, then try again.\nThe full error must stay readable on a narrow screen."
        ImageComposeScene(320, 700, Density(1f), content = {
            HarnessTheme(dark = false) {
                Column(Modifier.fillMaxSize().background(color(backgroundColor)).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Pill(error, tone = Tone.Danger, icon = Lucide.CircleX)
                    ErrorMessage(error)
                }
            }
        }).use { scene ->
            scene.settle()
            val texts = scene.nodes().filter { it.config.getOrNull(SemanticsProperties.Text)?.any { it.text == error } == true }
            assertEquals(2, texts.size)
            texts.forEach { node ->
                val layouts = mutableListOf<TextLayoutResult>()
                assertTrue(node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts))
                assertTrue(layouts.single().lineCount > 3)
                assertFalse(layouts.single().hasVisualOverflow)
            }
            scene.screenshot("multiline-errors")
        }
    }

    private fun ImageComposeScene.nodes(): List<SemanticsNode> {
        fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
        return semanticsOwners.flatMap { flatten(it.unmergedRootSemanticsNode) }
    }

    private fun ImageComposeScene.settle() {
        repeat(20) { render(it * 150_000_000L); Thread.sleep(15) }
    }

    private fun ImageComposeScene.screenshot(name: String) {
        File("build/screens").apply { mkdirs() }.resolve("$name.png")
            .writeBytes(render(4_000_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes)
    }
}
