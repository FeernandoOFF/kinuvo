package kinuvo.ui

import kinuvo.app.agent.*
import kinuvo.app.navigation.*
import kinuvo.app.onboarding.*
import kinuvo.app.session.*
import kinuvo.app.settings.*
import kinuvo.client.ConnectionState
import kinuvo.protocol.agent.*
import kinuvo.protocol.capability.ServerCapabilities
import kinuvo.protocol.capability.ServerFeature
import kinuvo.protocol.model.*
import kinuvo.protocol.session.*
import kinuvo.ui.agent.*
import kinuvo.ui.design.KinuvoTheme
import kinuvo.ui.navigation.*
import kinuvo.ui.onboarding.*
import kinuvo.ui.session.*
import kinuvo.ui.settings.*

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import com.composables.ui.theme.*
import kinuvo.ui.design.color
import kinuvo.ui.design.Spacing
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test

/** Phone renders of the sign-in, Agent sheet, drawer, chat actions and custom endpoint, for review. */
class PolishScreenshotTest {
    private val out = File("build/screens/polish").apply { mkdirs() }
    private val owners = listOf(AgentInfo(AgentId("echo"), "Echo", "host"), AgentInfo(AgentId("hearth"), "Hearth", "host"))
    private val models = ModelsCatalog(listOf(
        ProviderModels("codex", "ChatGPT / Codex", listOf(ModelInfo("gpt-5.6-sol", "GPT-5.6-Sol", listOf("low", "medium", "high"), "medium", tier = ModelTier.Frontier),
            ModelInfo("gpt-5.6-mini", "GPT-5.6-Mini", listOf("low", "medium"), "low"))),
        ProviderModels("openai", "OpenAI API", listOf(ModelInfo("gpt-5.5", "GPT-5.5"), ModelInfo("gpt-5.5-nano", "GPT-5.5-Nano"))),
        ProviderModels("local", "Local models", listOf(ModelInfo("custom-local", "Local model", emptyList(), tier = ModelTier.Small))),
    ))
    private val code = DeviceCode("https://auth.openai.com/codex/device", "ABCD-1234", 0)
    private val sessions = listOf(
        SessionInfo(SessionId("20261026-210000-journal"), AgentId("echo"), "Evening journal"),
        SessionInfo(SessionId("20261025-120000-finance"), AgentId("echo"), "Finance plans", favorite = true),
    )

    @Composable private fun phone(content: @Composable () -> Unit) = KinuvoTheme(dark = true) {
        CompositionLocalProvider(LocalInteractionMode provides InteractionMode.Touch) {
            Box(Modifier.fillMaxSize().background(color(backgroundColor))) { content() }
        }
    }

    @Test fun render() {
        val details = AgentDetails(owners[0], AgentSettings("gpt-5.6-sol", "medium", "default", "host", provider = "codex"), "Warm and curious.", "Be direct.")
        val inspected = InspectorState(AgentId("echo"), details = details, draft = AgentDraft.of(details), models = models)
        val host = InspectorHost(owners, ServerCapabilities("home", ServerFeature.entries.toSet()))
        render("agent-sheet", 390, 1100) { phone { AgentInspectorContent(inspected, host, InspectorActions(), sheet = true) } }
        render("agent-sheet-models", 390, 900) { phone { AgentInspectorContent(inspected.copy(pages = listOf(InspectorPage.Model)), host, InspectorActions(), sheet = true) } }
        val shell = ShellState(owners, owners[0], mapOf(AgentId("echo") to SessionList(sessions)), view = Route.Chat(AgentId("echo"), sessions[0].id))
        render("sidebar", 390, 500) { phone { Shell(shell, ShellActions(), remember { ShellChrome(mutableStateOf(true)) }, fill = true) } }
        val chat = ChatState(agent = owners[0], sessions = sessions, selected = sessions[0].id, loaded = true, defaults = details.settings, models = models, items = listOf(
            ChatItem.User("u1", "Hey, who are you?"),
            ChatItem.Assistant("a1", "I'm Echo. I help you think things through and get them done."),
        ))
        render("chat", 390, 500) { phone { ChatContent(chat, ConnectionState.Connected("home"), null, ChatActions(), wide = false, onToggleShell = {}) } }
        val providers = listOf(ProviderInfo("codex", "ChatGPT / Codex", ProviderAccount(false)), ProviderInfo("openai", "OpenAI API", apiKey = ProviderApiKeyStatus(false, "Not set")))
        render("signin-onboarding", 390, 900) {
            phone { Column(Modifier.verticalScroll(rememberScrollState()).padding(Spacing.space16), verticalArrangement = Arrangement.spacedBy(Spacing.space16)) {
                ModelStep(OnboardingState(step = 2, catalog = models, providers = providers, provider = "codex", model = "gpt-5.6-sol", deviceCode = code), {}, {}, {}, onEndpoint = { _, _ -> })
            } }
        }
        val ui = SettingsUi(SettingsState(Route.Settings(AgentId("echo"), SettingsSection.Providers), providers = providers, deviceCode = "codex" to code), owners)
        render("signin-settings", 390, 900) { phone { SettingsContent(ui, SettingsActions(onBack = {}), sidebar = false) } }
        render("ask-before", 390, 420) { phone { Column(Modifier.padding(Spacing.space16)) { ApprovalChoices("relaxed", "default") {} } } }
        val params = CustomEndpointParams(id = "lmstudio", baseUrl = "http://localhost:1234/v1", model = "local-chat")
        render("endpoint-empty", 390, 700) { phone { Column(Modifier.padding(Spacing.space16)) { EndpointContent(EndpointState(), {}, {}, {}) } } }
        render("endpoint-models", 390, 700) { phone { Column(Modifier.padding(Spacing.space16)) { EndpointContent(EndpointState(params, listOf(ModelInfo("local-chat"), ModelInfo("qwen3-8b")), discovered = true), {}, {}, {}) } } }
        render("endpoint-manual", 390, 700) { phone { Column(Modifier.padding(Spacing.space16)) {
            EndpointContent(EndpointState(params, discovered = true, reason = "No chat models were returned. Enter a Model ID to continue."), {}, {}, {})
        } } }
    }

    private fun render(name: String, width: Int, height: Int, content: @Composable () -> Unit) = javax.swing.SwingUtilities.invokeAndWait {
        ImageComposeScene(width * 2, height * 2, Density(2f), coroutineContext = Dispatchers.Main, content = content).use { scene ->
            repeat(5) { scene.render(it * 500_000_000L) }
            File(out, "$name.png").writeBytes(scene.render(3_000_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes)
        }
    }
}
