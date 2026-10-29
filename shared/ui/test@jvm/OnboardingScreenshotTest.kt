package kinuvo.ui

import kinuvo.app.connection.*
import kinuvo.app.agent.*
import kinuvo.ui.agent.*

import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size
import androidx.compose.ui.use
import com.composables.ui.components.Text
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import kinuvo.protocol.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.composables.icons.lucide.*
import kinuvo.ui.design.HarnessTheme
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class OnboardingScreenshotTest {
    @Test fun safeAreasKeepTheGlow() {
        render("onboarding-safe-areas", 390, 844) {
            HarnessTheme(dark = false) {
                OnboardingContent(OnboardingState(step = 4, avatar = 0), insets = WindowInsets(top = 59.dp, bottom = 34.dp))
            }
        }
        val image = javax.imageio.ImageIO.read(File("build/screens/onboarding-safe-areas.png"))
        val top = java.awt.Color(image.getRGB(390, 5))
        val bottom = java.awt.Color(image.getRGB(390, 1680))
        assertTrue(top.red < bottom.red && top.green < bottom.green, "The glow must extend behind the top safe area")
        val before = java.awt.Color(image.getRGB(390, 117))
        val after = java.awt.Color(image.getRGB(390, 119))
        assertTrue(kotlin.math.abs(before.green - after.green) < 3, "The glow must stay continuous across the safe area")
    }

    @Test fun avatarStills() {
        for (artboard in AvatarArtboards) for (state in AvatarState.entries) {
            render(kinuvo.ui.design.avatarStill(artboard, state), 320, 320, requireVisiblePixels = true) {
                kinuvo.ui.design.RiveAvatar(BundledAvatar, androidx.compose.ui.Modifier.size(320.dp), artboard = artboard, state = state)
            }
        }
    }

    @Test fun onboarding() {
        for (step in OnboardingSteps) {
            for ((width, height) in listOf(1100 to 850, 390 to 844)) {
                render("onboarding-$step-$width", width, height) {
                    HarnessTheme(dark = false) {
                        val state = OnboardingState(step = step, userName = "Alex", agentName = "Milo", avatar = 1,
                            provider = "codex", model = "gpt-5.6-sol",
                            catalog = ModelsCatalog(listOf(ProviderModels("codex", "OpenAI Codex", listOf(ModelInfo("gpt-5.6-sol", "GPT-5.6"))))),
                            providers = listOf(ProviderInfo("codex", "OpenAI Codex", account = ProviderAccount(false))))
                        val labels = mapOf(2 to "Sign in to OpenAI Codex", 6 to "Meet Milo")
                        OnboardingContent(state, action = OnboardingAction(labels[step] ?: "Continue"), welcome = { featured, pokes, poke, tint, insets ->
                            Welcome(WelcomeOptions(onScan = {}, onLocal = {}, onPair = {}), featured, pokes, poke, tint, insets)
                        }, model = { ModelStep(state, {}, {}, {}) },
                            connect = { ConnectList(state.copy(connected = mapOf(kinuvo.app.navigation.SettingsSection.Knowledge to true)),
                                { it != kinuvo.app.navigation.SettingsSection.Secrets && it != kinuvo.app.navigation.SettingsSection.Workspace }, {}) })
                    }
                }
            }
        }
        for (step in AgentSteps) render("new-agent-$step-390", 390, 844) {
            HarnessTheme(dark = false) { OnboardingContent(OnboardingState(step = step, steps = AgentSteps, agentName = "Wren", avatar = 3), onClose = {}) }
        }
        render("new-agent-chrome-1100", 1100, 850) {
            androidx.compose.runtime.CompositionLocalProvider(kinuvo.ui.design.LocalWindowChrome provides kinuvo.ui.design.WindowChrome(40.dp, 76.dp, 0.dp)) {
                HarnessTheme(dark = false) { OnboardingContent(OnboardingState(step = 4, steps = AgentSteps, agentName = "Wren", avatar = 3), onClose = {}) }
            }
        }
        val saved = listOf(Server.Remote("wss://home.example/ws", "token", "Home", "home"))
        render("onboarding-welcome-manual-390", 390, 844) {
            HarnessTheme(dark = true) {
                OnboardingContent(OnboardingState(), welcome = { featured, pokes, poke, tint, insets ->
                    Welcome(WelcomeOptions(onScan = {}, onLocal = null, onPair = {}, servers = saved, manual = true), featured, pokes, poke, tint, insets)
                })
            }
        }
        render("onboarding-welcome-dark-390", 390, 844) {
            HarnessTheme(dark = true) {
                OnboardingContent(OnboardingState(), welcome = { featured, pokes, poke, tint, insets ->
                    Welcome(WelcomeOptions(onScan = {}, onLocal = null, onPair = {}), featured, pokes, poke, tint, insets)
                })
            }
        }
        render("onboarding-welcome-desktop-1100", 1100, 850) {
            HarnessTheme(dark = false) {
                OnboardingContent(OnboardingState(), welcome = { featured, pokes, poke, tint, insets ->
                    Welcome(WelcomeOptions(onScan = null, onLocal = {}, onPair = {}, servers = saved), featured, pokes, poke, tint, insets)
                })
            }
        }
        render("onboarding-soul-dark", 1100, 1000) { HarnessTheme(dark = true) { OnboardingContent(OnboardingState(step = 5, avatar = 2)) } }
    }

    @Test fun theWelcomeAgentIsTheOneTheStepsShape() = javax.swing.SwingUtilities.invokeAndWait {
        val state = androidx.compose.runtime.mutableStateOf(OnboardingState())
        ImageComposeScene(390, 844, Density(1f), content = {
            HarnessTheme(dark = false) {
                OnboardingContent(state.value, onEdit = { state.value = it(state.value) }, welcome = { featured, pokes, poke, tint, insets ->
                    Welcome(WelcomeOptions(onScan = {}, onLocal = null, onPair = {}), featured, pokes, poke, tint, insets)
                })
            }
        }).use { scene ->
            var time = 0L
            fun frame(ms: Long = 100) { scene.render(time.also { time += ms * 1_000_000 }) }
            repeat(5) { frame() }
            val mint = AvatarArtboards[2]
            fun all(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::all)
            val target = scene.semanticsOwners.flatMap { all(it.unmergedRootSemanticsNode) }.first {
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(mint) == true && it.config.getOrNull(SemanticsActions.OnClick) != null
            }
            target.config[SemanticsActions.OnClick].action!!.invoke()
            repeat(5) { frame() }
            kotlin.test.assertEquals(2, state.value.avatar, "Tapping an Agent on the welcome picks it for the steps")
            state.value = state.value.copy(step = 2)
            // Mid-flight frames, kept for checking the move by eye.
            for (i in 1..4) {
                frame(90)
                File("build/screens/onboarding-flight-$i.png").writeBytes(scene.render(time).encodeToData(EncodedImageFormat.PNG)!!.bytes)
            }
            repeat(10) { frame() }
            // Back to the welcome and forward again, with the same Agent introducing itself.
            state.value = state.value.copy(step = 0)
            repeat(12) { frame(50) }
            kotlin.test.assertEquals(2, state.value.avatar)
            state.value = state.value.copy(step = 2)
            repeat(12) { frame(50) }
        }
    }

    private fun render(name: String, width: Int, height: Int, requireVisiblePixels: Boolean = false, content: @Composable () -> Unit) = javax.swing.SwingUtilities.invokeAndWait {
        ImageComposeScene(width * 2, height * 2, Density(2f), content = content).use { scene ->
            repeat(20) { scene.render(it * 150_000_000L); Thread.sleep(15) }
            val bytes = scene.render(3_000_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes
            if (requireVisiblePixels) {
                val image = javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(bytes))
                assertTrue(image.getRGB(0, 0, image.width, image.height, null, 0, image.width).any { it ushr 24 != 0 }, "$name rendered an empty image")
            }
            File("build/screens").apply { mkdirs() }.resolve("$name.png").writeBytes(bytes)
        }
    }
}
