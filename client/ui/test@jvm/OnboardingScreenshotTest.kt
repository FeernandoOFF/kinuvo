package kinuvo.ui

import kinuvo.app.connection.*
import kinuvo.app.agent.*
import kinuvo.app.onboarding.*
import kinuvo.app.settings.EndpointState
import kinuvo.ui.agent.*
import kinuvo.ui.settings.*
import kinuvo.ui.onboarding.*

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
import kinuvo.protocol.model.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.composables.icons.lucide.*
import kinuvo.ui.design.KinuvoTheme
import kinuvo.ui.design.ChatGptLogo
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class OnboardingScreenshotTest {
    @Test fun endpointDiscoveryStates() {
        val params = CustomEndpointParams(baseUrl = "http://localhost:1234/v1", model = "local-chat")
        val states = listOf(
            "loading" to EndpointState(params, busy = true),
            "models" to EndpointState(params, listOf(ModelInfo("local-chat")), discovered = true),
            "manual" to EndpointState(params, discovered = true, reason = "No chat models were returned. Enter a Model ID to continue."),
        )
        for ((name, state) in states) render("onboarding-endpoint-$name", 390, 844) {
            KinuvoTheme(dark = false) { EndpointContent(state, {}, {}, {}) }
        }
    }

    @Test fun modelStates() {
        val catalog = ModelsCatalog(listOf(
            ProviderModels("codex", "OpenAI Codex", listOf(ModelInfo("gpt-6.2-sol", "GPT-6.2 Sol"), ModelInfo("gpt-6.2-astra", "GPT-6.2 Astra"))),
            ProviderModels("apple", "Apple", listOf(ModelInfo("apple-foundation", "Apple Foundation")))))
        fun providers(signedIn: Boolean, plan: Boolean = false, allowed: Boolean = true) = listOf(
            ProviderInfo("codex", "ChatGPT / Codex", account = ProviderAccount(signedIn, "alex@example.com".takeIf { signedIn }, usesChatGptPlan = plan, planAllowed = allowed), browserSignIn = true),
            ProviderInfo("apple", "Apple"), ProviderInfo("openai", "OpenAI", apiKey = ProviderApiKeyStatus(false, "Server")))
        val base = OnboardingState(step = 2, avatar = 1, catalog = catalog, providers = providers(false), provider = "codex", model = "gpt-6.2-sol")
        val url = "https://auth.openai.com/api/accounts/authorize?client_id=dynamic_agent_client"
        val chatGpt = OnboardingAction("Continue with ChatGPT").apply { icon = ChatGptLogo }
        val again = OnboardingAction("Open ChatGPT again").apply { icon = Lucide.ExternalLink }
        val states = listOf(
            Triple("chatgpt-continue", base, chatGpt),
            Triple("chatgpt-waiting", base.copy(providerSignIn = ProviderSignInState("codex", url)), again),
            Triple("chatgpt-paste", base.copy(providerSignIn = ProviderSignInState("codex", url, awaitingPaste = true)), again),
            Triple("chatgpt-error", base.copy(providerSignIn = ProviderSignInState("codex", url, awaitingPaste = true, error = "ChatGPT plan use wasn't allowed. Try again and allow it.")), again),
            Triple("chatgpt-welcome", base.copy(providers = providers(true, plan = true)), OnboardingAction("Continue")),
            Triple("chatgpt-blocked", base.copy(providers = providers(true, plan = true, allowed = false)),
                OnboardingAction("Allow ChatGPT plan usage").apply { icon = ChatGptLogo }),
            Triple("chatgpt-ready", base.copy(providers = providers(true, plan = true)), OnboardingAction("Continue")),
            Triple("codex-code", base.copy(deviceCode = DeviceCode("https://auth.openai.com/codex/device", "K7QX-9MDP", 0)),
                OnboardingAction("Copy code and sign in").apply { icon = Lucide.ExternalLink }),
            Triple("codex-loading", base.copy(providers = providers(true, plan = true), catalog = ModelsCatalog(catalog.providers.drop(1)), model = "", busy = true), OnboardingAction("Continue", false)),
            Triple("api-key", base.copy(providers = providers(true), provider = "openai", model = ""), OnboardingAction("Continue", false)),
            Triple("apple", base.copy(providers = providers(true), provider = "apple", model = "apple-foundation"), OnboardingAction("Continue")),
        )
        for ((name, state, action) in states) for (height in listOf(844, 1300)) render("onboarding-model-$name-${if (height == 844) "390" else "full"}", 390, height) {
            KinuvoTheme(dark = false) {
                OnboardingContent(state, action = action, model = { ModelStep(state, {}, {}, {}, chatGpt = ChatGptActions(welcome = name == "chatgpt-welcome")) })
            }
        }
        render("onboarding-model-chatgpt-continue-dark", 390, 844) {
            KinuvoTheme(dark = true) { OnboardingContent(base, action = chatGpt, model = { ModelStep(base, {}, {}, {}) }) }
        }
    }

    @Test fun safeAreasKeepTheGlow() {
        render("onboarding-safe-areas", 390, 844) {
            KinuvoTheme(dark = false) {
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
                    KinuvoTheme(dark = false) {
                        val state = OnboardingState(step = step, userName = "Alex", agentName = "Milo", avatar = 1,
                            provider = "codex", model = "gpt-5.6-sol",
                            catalog = ModelsCatalog(listOf(ProviderModels("codex", "OpenAI Codex", listOf(ModelInfo("gpt-5.6-sol", "GPT-5.6"))))),
                            providers = listOf(ProviderInfo("codex", "OpenAI Codex", account = ProviderAccount(false)),
                                ProviderInfo("apple", "Apple"), ProviderInfo("openai", "OpenAI", apiKey = ProviderApiKeyStatus(false, "Server"))))
                        val labels = mapOf(2 to "Sign in to OpenAI Codex", 6 to "Meet Milo")
                        OnboardingContent(state, action = OnboardingAction(labels[step] ?: "Continue"), welcome = { featured, pokes, poke, tint, insets ->
                            Welcome(WelcomeOptions(onScan = {}, onLocal = {}, onPair = {}), featured, pokes, poke, tint, insets)
                        }, model = { ModelStep(state, {}, {}, {}) },
                            connect = { ConnectList(state.copy(connected = mapOf(kinuvo.app.navigation.SettingsSection.Knowledge to false, kinuvo.app.navigation.SettingsSection.Tasks to false,
                                kinuvo.app.navigation.SettingsSection.Notifications to true)),
                                { it != kinuvo.app.navigation.SettingsSection.Secrets }, {}) })
                    }
                }
            }
        }
        for (step in AgentSteps) render("new-agent-$step-390", 390, 844) {
            KinuvoTheme(dark = false) { OnboardingContent(OnboardingState(step = step, steps = AgentSteps, agentName = "Wren", avatar = 3), onClose = {}) }
        }
        render("new-agent-chrome-1100", 1100, 850) {
            androidx.compose.runtime.CompositionLocalProvider(kinuvo.ui.design.LocalWindowChrome provides kinuvo.ui.design.WindowChrome(40.dp, 76.dp, 0.dp)) {
                KinuvoTheme(dark = false) { OnboardingContent(OnboardingState(step = 4, steps = AgentSteps, agentName = "Wren", avatar = 3), onClose = {}) }
            }
        }
        val saved = listOf(Server.Remote("wss://home.example/ws", "token", "Home", "home"))
        render("onboarding-welcome-manual-390", 390, 844) {
            KinuvoTheme(dark = true) {
                OnboardingContent(OnboardingState(), welcome = { featured, pokes, poke, tint, insets ->
                    Welcome(WelcomeOptions(onScan = {}, onLocal = null, onPair = {}, servers = saved, manual = true), featured, pokes, poke, tint, insets)
                })
            }
        }
        render("onboarding-welcome-dark-390", 390, 844) {
            KinuvoTheme(dark = true) {
                OnboardingContent(OnboardingState(), welcome = { featured, pokes, poke, tint, insets ->
                    Welcome(WelcomeOptions(onScan = {}, onLocal = null, onPair = {}), featured, pokes, poke, tint, insets)
                })
            }
        }
        render("onboarding-welcome-desktop-1100", 1100, 850) {
            KinuvoTheme(dark = false) {
                OnboardingContent(OnboardingState(), welcome = { featured, pokes, poke, tint, insets ->
                    Welcome(WelcomeOptions(onScan = null, onLocal = {}, onPair = {}, servers = saved), featured, pokes, poke, tint, insets)
                })
            }
        }
        render("onboarding-soul-dark", 1100, 1000) { KinuvoTheme(dark = true) { OnboardingContent(OnboardingState(step = 5, avatar = 2)) } }
    }

    @Test fun theWelcomeAgentIsTheOneTheStepsShape() = javax.swing.SwingUtilities.invokeAndWait {
        val state = androidx.compose.runtime.mutableStateOf(OnboardingState())
        ImageComposeScene(390, 844, Density(1f), content = {
            KinuvoTheme(dark = false) {
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
