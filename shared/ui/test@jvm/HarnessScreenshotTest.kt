package kinuvo.ui

import kinuvo.app.agent.*
import kinuvo.app.navigation.*
import kinuvo.app.session.*
import kinuvo.ui.agent.*
import kinuvo.ui.session.*

import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import kinuvo.client.ConnectionState
import kinuvo.protocol.*
import kinuvo.ui.design.HarnessTheme
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test

/** ACP Agent setup and Sessions, rendered to `build/screens/harness-*.png`. */
class HarnessScreenshotTest {
    private val out = File("build/screens").apply { mkdirs() }
    private val server = "Mac mini"
    private val capabilities = ServerCapabilities("mac", integrations = mapOf("Harness" to setOf("acp", "remote"), "Exec" to setOf("host"), "Mcp" to setOf("stdio", "http")))
    private val claude = HarnessConnectionStatus(HarnessConnection("claude", "Claude Code", ExecutionConnection.Acp(listOf("claude-code-acp"))), ExecutionReadiness(true, true))
    private val codex = HarnessConnectionStatus(HarnessConnection("codex", "Codex", ExecutionConnection.Acp(listOf("codex-acp", "--profile", "work"))),
        ExecutionReadiness(true, false, "Not signed in", "needs_sign_in"))
    private val gemini = HarnessConnectionStatus(HarnessConnection("gemini", "Gemini CLI", ExecutionConnection.Acp(listOf("gemini", "--experimental-acp"))),
        ExecutionReadiness(true, false, "No such file", "not_found"))
    private val studio = HarnessConnectionStatus(HarnessConnection("studio", "Studio", ExecutionConnection.Remote("studio"), "Studio"),
        ExecutionReadiness(true, false, "Remote Server unavailable", "host_offline"))
    private val kinuvo = AgentInfo("main", "Kinuvo", "host")
    private val reviewer = AgentInfo("reviewer", "Reviewer", "external", "Coral Bloom", ExecutionKind.Acp, "codex", server, codex.readiness)
    private val builder = AgentInfo("builder", "Builder", "external", "Sky Peak", ExecutionKind.Acp, "claude", server, claude.readiness)
    private val agents = listOf(kinuvo, builder, reviewer)
    private val catalog = ExecutionCatalog(listOf(claude, codex, gemini, studio), listOf(
        AgentRegistration("milo", "Milo", "studio", remoteAgentId = "milo"),
        AgentRegistration("builder", "Builder", "claude", cwd = "/Users/fer/dev/kinuvo"), AgentRegistration("reviewer", "Reviewer", "codex", cwd = "/Users/fer/dev/kinuvo")))
    private val harnesses = HarnessesState(catalog)

    private fun settings(route: Route.Settings, acp: AcpForm? = null) =
        SettingsUi(SettingsState(route, harnesses = harnesses, acp = acp), agents, capabilities = capabilities, serverName = server)

    @Test fun renderSettings() {
        render("harness-settings-list-desktop", 1200, 760) { theme { SettingsContent(settings(Route.Settings("main", SettingsSection.Harnesses)), SettingsActions()) } }
        render("harness-settings-detail-phone", 390, 844) {
            theme { SettingsContent(settings(Route.Settings("main", SettingsSection.Harnesses, "codex")), SettingsActions(), sidebar = false) }
        }
        val form = AcpForm(name = "Codex", command = "codex-acp", args = listOf("--profile", "work"), environment = listOf("CODEX_HOME" to "/Users/test/.codex-work"))
        val probed = form.copy(probe = ExecutionProbe(ExecutionReadiness(true, false, "Not signed in", "needs_sign_in"), "Codex", "0.9.2", authenticated = false,
            Json.parseToJsonElement("""[{"id":"chatgpt","name":"ChatGPT account"},{"id":"api","name":"API key"}]""")), probed = form.acp)
        render("harness-settings-acp-form-phone", 390, 844) {
            theme { SettingsContent(settings(Route.Settings("main", SettingsSection.Harnesses, editing = true), probed), SettingsActions(), sidebar = false) }
        }
        render("harness-settings-acp-form-unchecked-phone", 390, 700) {
            theme { SettingsContent(settings(Route.Settings("main", SettingsSection.Harnesses, editing = true), AcpForm(name = "Gemini", command = "gemini --acp")), SettingsActions(), sidebar = false) }
        }
        render("harness-settings-link-phone", 390, 760) {
            theme { SettingsContent(settings(Route.Settings("main", SettingsSection.Harnesses, LinkItem, editing = true)).let {
                it.copy(state = it.state.copy(link = LinkForm("Studio", "https://studio.example.com", "4f1c…")))
            }, SettingsActions(), sidebar = false) }
        }
        render("harness-settings-share-phone", 390, 760) {
            theme { SettingsContent(settings(Route.Settings("main", SettingsSection.Harnesses, ShareItem, editing = true)).let {
                it.copy(state = it.state.copy(share = ShareForm("Studio", setOf("main", "builder")), grants = listOf(ExposureGrant("laptop", setOf("main")))))
            }, SettingsActions(), sidebar = false) }
        }
        render("harness-settings-share-code-phone", 390, 760) {
            theme { SettingsContent(settings(Route.Settings("main", SettingsSection.Harnesses, ShareItem, editing = true)).let {
                it.copy(state = it.state.copy(share = ShareForm("Studio", setOf("main", "builder"), "0f9c2e7a1b4d4e8f9a6b3c2d1e0f9a8b7c6d5e4f", "studio"), grants = listOf(ExposureGrant("studio", setOf("main", "builder")))))
            }, SettingsActions(), sidebar = false) }
        }
        val overview = SettingsUi(SettingsState(Route.Settings("reviewer", SettingsSection.Overview), AgentConfig(details(reviewer)), harnesses = harnesses), agents,
            capabilities = capabilities, serverName = server)
        render("harness-settings-overview-acp-phone", 390, 844) { theme { SettingsContent(overview, SettingsActions(), sidebar = false) } }
    }

    @Test fun renderNewAgent() {
        val start = OnboardingState(step = HarnessStep, steps = agentSteps(HarnessChoice.Kinuvo), agentName = "Wren", avatar = 3, connections = catalog.connections, server = server)
        val choose: @Composable (OnboardingState, Boolean) -> Unit = { s, acp -> HarnessChoices(s, acp, acp, agents, {}) {} }
        render("harness-new-agent-where-390", 390, 900) { theme { OnboardingContent(start, onClose = {}, harness = { choose(start, true) }) } }
        val loading = start.copy(connections = null)
        render("harness-new-agent-where-loading-390", 390, 844) { theme { OnboardingContent(loading, onClose = {}, harness = { choose(loading, true) }) } }
        val picked = start.choose(HarnessChoice.Acp("claude"))
        render("harness-new-agent-where-picked-1100", 1100, 850) { theme { OnboardingContent(picked, onClose = {}, harness = { choose(picked, true) }) } }
        val phone = start.copy(server = "this device", connections = emptyList())
        render("harness-new-agent-where-embedded-390", 390, 844) { theme { OnboardingContent(phone, onClose = {}, harness = { choose(phone, false) }) } }
        val remote = start.choose(HarnessChoice.Remote("studio")).copy(step = PickStep, registrations = catalog.agents,
            shared = listOf(AgentInfo("ada", "Ada", "host", "Mint Pill"), AgentInfo("research", "Kinuvo", "host", "Honey Star"), AgentInfo("milo", "Milo", "host", "Berry Orb")),
            picked = setOf("ada"))
        render("harness-new-agent-pick-remote-390", 390, 844) {
            theme { OnboardingContent(remote, action = OnboardingAction("Add 1 Agent"), onClose = {}, pick = { PickAgents(remote) {} }) }
        }
        val identity = picked.copy(step = 4, cwd = "/Users/fer/dev/kinuvo")
        render("harness-new-agent-acp-identity-390", 390, 844) {
            theme { OnboardingContent(identity, action = OnboardingAction("Meet Wren"), onClose = {}) }
        }
        render("harness-new-agent-acp-identity-invalid-390", 390, 844) {
            theme { OnboardingContent(identity.copy(cwd = "dev/kinuvo"), action = OnboardingAction("Meet Wren", false), onClose = {}) }
        }
    }

    @Test fun renderInspector() {
        val details = details(reviewer)
        val state = InspectorState("reviewer", details = details, draft = AgentDraft.of(details).copy(cwd = "/Users/fer/dev/other"), connection = codex)
        val host = InspectorHost(agents, capabilities, server)
        render("harness-inspector-acp", 368, 900) { theme { AgentInspectorContent(state, host, InspectorActions(), sheet = false) } }
        render("harness-inspector-acp-sheet-phone", 390, 844) { theme(touch = true) { AgentInspectorContent(state, host, InspectorActions(), sheet = true) } }
    }

    @Test fun renderSession() {
        val approval = ExecutionRequest("p1", ExecutionRequestKind.Approval, buildJsonObject { put("title", "Run ./kotlin build"); put("description", "In /Users/fer/dev/kinuvo") },
            listOf(ExternalApprovalOption("once", "Allow once", "allow_once"), ExternalApprovalOption("always", "Always allow here", "allow_always"), ExternalApprovalOption("no", "Reject", "reject_once")))
        val form = ExecutionRequest("q1", ExecutionRequestKind.Question, Json.parseToJsonElement("""{"mode":"form","message":"Which branch should I review?","requestedSchema":{"type":"object","required":["branch"],"properties":{
            "branch":{"type":"string","title":"Branch","oneOf":[{"const":"main","title":"main"},{"const":"acp-v2","title":"acp-v2"}]},
            "fix":{"type":"boolean","title":"Fix lint too?","default":true}}}}"""))
        val base = ChatState(agent = builder, harness = "Claude Code", selected = "s1", loaded = true, running = true, defaults = AgentSettings("", "", "", "external"),
            capabilities = SessionCapabilities(server = capabilities),
            items = listOf(ChatItem.User("u1", "Review the open PR and fix lint."), ChatItem.Assistant("a1", "Looking at the diff. I'll run the build first.")))
        render("harness-chat-approval-phone", 390, 844) { theme(touch = true) { ChatContent(base.copy(prompts = listOf(approval.prompt())), ConnectionState.Connected("x"), null, ChatActions()) } }
        render("harness-chat-form-desktop", 900, 760) { theme { ChatContent(base.copy(prompts = listOf(form.prompt())), ConnectionState.Connected("x"), null, ChatActions(), wide = true) } }
        val openUrl = ExecutionRequest("p2", ExecutionRequestKind.Approval, Json.parseToJsonElement("""{"title":"MCP server requests to open a URL","subject":{"type":"tool_call","toolCall":{
            "toolCallId":"t","kind":"fetch","status":"pending","rawInput":{"serverName":"codex_apps","description":"Reconnect Slack on ChatGPT to restore access for this request.","url":"https://chatgpt.com/connect?token=x"}}}}"""),
            listOf(ExternalApprovalOption("accept", "Allow", "allow_once"), ExternalApprovalOption("decline", "Deny", "reject_once"), ExternalApprovalOption("cancel", "Cancel", "reject_once")))
        render("harness-chat-open-url-desktop", 900, 760) { theme { ChatContent(base.copy(agent = reviewer, harness = "Codex", prompts = listOf(openUrl.prompt())), ConnectionState.Connected("x"), null, ChatActions(), wide = true) } }
        for (support in listOf(true, false, null)) render("harness-chat-ended-$support-phone", 390, 844) {
            theme(touch = true) { ChatContent(base.copy(running = false, phase = ExecutionPhase.Lost, resume = support), ConnectionState.Connected("x"), null, ChatActions()) }
        }
        render("harness-chat-close-active-phone", 390, 500) {
            theme(touch = true) { CloseSessionConfirm(base.copy(resume = true), {}, {}) }
        }
        val offline = base.copy(agent = reviewer, harness = "Codex", running = false, unsent = "and fix lint when you're done",
            capabilities = SessionCapabilities(server = capabilities, chat = CapabilityState.unavailable("Not signed in", "needs_sign_in")))
        render("harness-chat-not-ready-phone", 390, 844) { theme(touch = true) { ChatContent(offline, ConnectionState.Connected("x"), null, ChatActions()) } }
    }

    private fun details(agent: AgentInfo) = AgentDetails(agent, AgentSettings("", "", "", "external"), "", "", managed = true, cwd = "/Users/fer/dev/kinuvo")

    @Composable
    private fun theme(touch: Boolean = false, content: @Composable () -> Unit) = HarnessTheme(dark = false) {
        if (touch) androidx.compose.runtime.CompositionLocalProvider(com.composables.ui.theme.LocalInteractionMode provides com.composables.ui.theme.InteractionMode.Touch, content = content)
        else content()
    }

    private fun render(name: String, width: Int, height: Int, content: @Composable () -> Unit) = javax.swing.SwingUtilities.invokeAndWait {
        ImageComposeScene(width * 2, height * 2, Density(2f), content = content).use { scene ->
            repeat(5) { scene.render(it * 500_000_000L) }
            File(out, "$name.png").writeBytes(scene.render(3_000_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes)
        }
    }
}
