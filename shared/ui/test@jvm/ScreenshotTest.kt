package kinuvo.ui

import kinuvo.app.connection.*
import kinuvo.app.session.*
import kinuvo.app.agent.*
import kinuvo.app.board.*
import kinuvo.app.knowledge.*
import kinuvo.app.navigation.*
import kinuvo.app.artifacts.*
import kinuvo.ui.connection.*
import kinuvo.ui.session.*
import kinuvo.ui.agent.*
import kinuvo.ui.board.*
import kinuvo.ui.knowledge.*
import kinuvo.ui.artifacts.*
import kinuvo.ui.routine.*
import kinuvo.ui.navigation.*

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.ui.theme.backgroundColor
import com.composables.ui.components.Text
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import kinuvo.client.ConnectionState
import kinuvo.protocol.AgentEvent
import kinuvo.protocol.CapabilityState
import kinuvo.protocol.ServerCapabilities
import kinuvo.protocol.SessionCapabilities
import kinuvo.protocol.SessionInfo
import kinuvo.protocol.SessionStatus
import kinuvo.protocol.Usage
import kinuvo.protocol.AgentInfo
import kinuvo.protocol.ActiveHours
import kinuvo.protocol.Routine
import kinuvo.protocol.RunStatus
import kinuvo.protocol.*
import kinuvo.ui.design.HarnessTheme
import kinuvo.ui.design.Markdown
import kinuvo.ui.design.color
import org.jetbrains.skia.EncodedImageFormat
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.io.File
import kotlin.test.Test

/** Renders the main screens to `build/screens/` for eyeballing UI changes: `./kotlin test -m ui -p jvm`. */
class ScreenshotTest {
    private val out = File("build/screens").apply { mkdirs() }

    private val agent = AgentInfo("main", "Kinuvo", "host")
    private val agents = listOf(agent, AgentInfo("research", "Researcher", "container"), AgentInfo("home", "Hearth", "host"))
    private val today = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE)
    private val earlier = java.time.LocalDate.now().minusDays(3).format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE)
    private val sessions = listOf(
        SessionInfo("$today-101500-a1", "main", "Refactor the exec backend"),
        SessionInfo("$today-090000-a2", "main", "What skills do you have access to?", SessionStatus.Running),
        SessionInfo("$today-080000-a5", "main", "Morning brief", routine = RoutineRef("r1", "Morning brief"), answered = true),
        SessionInfo("$earlier-170000-a3", "main", "Weekly review feature", favorite = true),
        SessionInfo("$earlier-120000-a4", "main", "Clean up the build", SessionStatus.Waiting),
        SessionInfo("20250101-120000-a6", "main", "Plan the Lisbon trip", task = "t1"),
        SessionInfo("20250101-090000-a7", "main", "Inbox triage"),
        SessionInfo("20241201-090000-a8", "main", "Old chat"),
    )
    private val state = ChatState(
        agent = agent,
        sessions = sessions,
        selected = sessions.first().id,
        running = true,
        items = listOf(
            ChatItem.User("u1", "Can you check what's **uncommitted** in the repo and summarise it?"),
            ChatItem.Reasoning("r1", "The user wants a summary of git status. I'll run git status first."),
            ChatItem.ToolCall("t1", "t1", "shell", """{"command":"git status --short"}""", result = " M core/src/Harness.kt\n?? ui/src/design/", identity = ToolIdentity(PluginInstanceId("kinuvo.exec"), "shell")),
            ChatItem.ToolCall("t2", "t2", "github__list_pull_requests", """{"repo":"harness"}""", result = "[]", identity = ToolIdentity(PluginInstanceId("kinuvo.mcp", "shared/github"), "list_pull_requests")),
            ChatItem.Assistant(
                "a1",
                """
                Here's what's uncommitted:

                1. `core/src/Harness.kt` — **modified**, wiring for the new exec backend
                2. `ui/src/design/` — a new *design system* package

                | File | Status |
                |------|--------|
                | Harness.kt | modified |
                | design/ | new |

                ```kotlin
                fun main() = println("hello")
                ```

                See [the docs](https://example.com) for more.
                """.trimIndent(),
            ),
            ChatItem.ToolCall(
                "t3", "t3", "shell", """{"command":"git commit -am wip"}""", identity = ToolIdentity(PluginInstanceId("kinuvo.exec"), "shell"),
                approval = AgentEvent.ApprovalRequested("a1", "t3", "shell", """{"command":"git commit -am wip"}""", "git commit", Risk.Medium, "git can change things"),
            ),
            ChatItem.Error("e1", "Tool failed: permission denied"),
        ),
    )
    private val usage = Usage("codex", listOf(Usage.Window("primary", 42.0, 300), Usage.Window("secondary", 91.0, 10080)))
    private val servers = ServerMenuState(
        servers = listOf(Server.Embedded, Server.Remote("ws://home:8787/ws", "token")),
        active = Server.Embedded,
        pairing = PairingInfo("123456", kotlin.time.Clock.System.now() + kotlin.time.Duration.parse("10m"), listOf("http://192.168.1.12:8787", "http://100.82.82.99:8787")),
    )

    private val now = 1_790_000_000_000L
    private val routines = listOf(
        Routine("heartbeat", "main", "Heartbeat", "- Anything due in my calendar?\n- Unread mail from family", Trigger.Every(30), "Europe/Dublin",
            activeHours = ActiveHours("08:00", "22:00"), heartbeat = true, nextRunAt = now + 12 * 60_000, lastRunAt = now - 18 * 60_000, lastStatus = RunStatus.Silent),
        Routine("r1", "main", "Morning brief", "Summarize my unread email and today's calendar. Keep it under 10 bullets.", Trigger.Cron("0 9 * * MON-FRI"), "Europe/Dublin",
            nextRunAt = now + 14 * 3_600_000, lastRunAt = now - 10 * 3_600_000, lastStatus = RunStatus.Ok, lastSessionId = "20260920-090000-ab12"),
        Routine("r2", "main", "Release watcher", "Check GitHub for new Kotlin releases.", Trigger.Every(360), "Europe/Dublin",
            lastRunAt = now - 3_600_000, lastStatus = RunStatus.Error, lastError = "web_fetch: connection timed out", nextRunAt = now + 5 * 3_600_000),
        Routine("r3", "main", "Call the dentist", "Remind me to call the dentist.", Trigger.Once(now - 3_600_000), "Europe/Dublin", enabled = false, lastRunAt = now - 3_600_000, lastStatus = RunStatus.Ok),
    )
    private val config = AgentConfig(
        details = AgentDetails(
            agents.first(), AgentSettings("gpt-5.6-sol", "medium", "default", "host", listOf("~/projects"), listOf("memory", "web_fetch", "skills", "schedule")),
            "# Kinuvo\n\nA personal assistant. Curious, warm, direct. Emoji: 🧭\n", "- Be concise; skip filler and flattery.\n- Say when you are unsure.\n",
            TrustRules(shell = listOf("git commit", "./kotlin test"), deny = DenyRules(shell = listOf("git push --force*"))),
        ),
        skills = listOf(
            SkillInfo("weekly-review", "Run the Friday review of open loops and next week's calendar."),
            SkillInfo("pdf", "Read, fill and merge PDF files.", scope = ConfigScope.Shared),
            SkillInfo("code-review", "Review a diff for bugs and style.", userInvocable = true, modelInvocable = false, scope = ConfigScope.External),
        ),
        mcp = listOf(
            McpServerInfo("github", url = "https://api.githubcopilot.com/mcp/", headers = mapOf("Authorization" to "Bearer \${GITHUB_TOKEN}")),
            McpServerInfo("linear", url = "https://mcp.linear.app/mcp", auth = McpAuthStatus.Required),
            McpServerInfo("fs", ConfigScope.Shared, command = "npx", args = listOf("-y", "@modelcontextprotocol/server-filesystem", "~/notes"), approval = "ask"),
        ),
    )
    private val settings = SettingsUi(
        SettingsState(
            Route.Settings("main", SettingsSection.Routines, "r1"), config, routines,
            runs = listOf(SessionInfo("20260920-090000-ab12", "main", "Morning brief"), SessionInfo("20260919-090000-cd34", "main", "Morning brief")),
            skill = SkillDetails(config.skills!!.first(), "---\nname: weekly-review\ndescription: Run the Friday review.\n---\n\n1. List open loops.\n2. Check next week's calendar.\n", listOf("checklist.md")),
            mcpTest = McpTestResult(listOf("search_issues", "create_issue", "get_pull_request")),
        ),
        agents, now,
    )

    private val modelCatalog = ModelsCatalog(listOf(
        ProviderModels("codex", "ChatGPT / Codex", listOf(ModelInfo("gpt-5.6-sol", "GPT-5.6-Sol", listOf("low", "medium", "high"), "medium"))),
        ProviderModels("local", "Local models", listOf(ModelInfo("custom-local", "Local model", emptyList()))),
    ))
    private fun settingsAt(route: Route.Settings) = settings.copy(state = settings.state.copy(route = route, models = modelCatalog))

    @Test
    fun renderPairing() {
        for (dark in listOf(false, true)) {
            val suffix = if (dark) "dark" else "light"
            render("connect-prefilled-$suffix", 390, 844) {
                HarnessTheme(dark = dark) {
                    ConnectScreen(null, { _, _, _ -> }, emptyList(), {}, pending = PairLink("http://192.168.1.12:8787", "123456"))
                }
            }
            val saved = listOf(Server.Remote("ws://192.168.1.12:8787/ws", "token", "Home", "home"), Server.Remote("wss://work.example/ws", null, "Work"), Server.Embedded)
            render("connect-servers-$suffix", 390, 1100) {
                HarnessTheme(dark = dark) { ConnectScreen("Couldn't reach 192.168.1.40:8787", { _, _, _ -> }, saved, {}, initialUrl = "192.168.1.40:8787", onCancel = {}) }
            }
            render("connect-connecting-$suffix", 1100, 900) {
                HarnessTheme(dark = dark) { ConnectScreen(null, { _, _, _ -> }, saved, {}, pending = PairLink("http://192.168.1.12:8787", "123456"), connecting = saved[0]) }
            }
            render("pairing-qr-$suffix", 340, 650) {
                HarnessTheme(dark = dark) {
                    Column(Modifier.fillMaxSize().background(color(backgroundColor)).padding(16.dp)) {
                        PairingPanel(servers.pairing!!, "http://192.168.1.12:8787", {}, {})
                    }
                }
            }
            val paired = listOf(
                PairedDeviceInfo("phone", "Pixel 8", "2026-10-08T12:00:00Z", connected = true, current = true, tools = setOf(DeviceToolKind.Contacts, DeviceToolKind.Workouts)),
                PairedDeviceInfo("tablet", "iPad", "2026-10-01T12:00:00Z", lastSeen = "2026-10-09T18:30:00Z"),
            )
            render("paired-devices-$suffix", 390, 650) {
                HarnessTheme(dark = dark) {
                    Column(Modifier.fillMaxSize().background(color(backgroundColor)).padding(16.dp)) { PairedDevices(DevicesState(paired), {}) }
                }
            }
            render("connection-settings-$suffix", 1000, 1100) {
                HarnessTheme(dark = dark) {
                    SettingsContent(settingsAt(Route.Settings("main", SettingsSection.Connection)).copy(
                        capabilities = ServerCapabilities("home", setOf(ServerFeature.Pairing)),
                        server = Server.Remote("ws://192.168.1.12:8787/ws", "token", "Home", "home"),
                        pairing = servers.pairing, devices = DevicesState(paired), servers = saved,
                    ), SettingsActions())
                }
            }
        }
    }

    /** Scenes render on the AWT thread, where desktop Compose dispatches effects. */
    @Test
    fun renderScreens() {
        render("chat-wide-light", 1200, 900) { HarnessTheme(dark = false) { ChatContent(state, ConnectionState.Connected("x"), usage, ChatActions()) } }
        render("chat-wide-dark", 1200, 900) { HarnessTheme(dark = true) { ChatContent(state, ConnectionState.Disconnected("x"), usage, ChatActions()) } }
        val defaults = AgentSettings("gpt-5.6-sol", "medium", "default", "host")
        render("composer-dark", 390, 152) {
            HarnessTheme(dark = true) {
                Column(Modifier.fillMaxSize().background(color(backgroundColor)).padding(12.dp)) {
                    kinuvo.ui.design.Composer(false, {}, {}, placeholder = "Chat with Kinuvo",
                        controls = { ComposerSettings(SessionSettings(), defaults, ModelsCatalog(listOf(ProviderModels("codex", "ChatGPT / Codex", listOf(ModelInfo(defaults.model))))), {}) })
                }
            }
        }
        val file = UploadAttachmentParams("journal.md", kotlin.io.encoding.Base64.encode("Today's workout".encodeToByteArray()))
        val png = java.io.ByteArrayOutputStream().also { javax.imageio.ImageIO.write(java.awt.image.BufferedImage(64, 64, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", it) }.toByteArray()
        val photo = UploadAttachmentParams("screenshot.png", kotlin.io.encoding.Base64.encode(png))
        render("chat-attachments", 420, 860) {
            HarnessTheme(dark = true) {
                ChatContent(state.copy(running = false, defaults = defaults, attachments = listOf(PendingAttachment("draft", file), PendingAttachment("photo", photo)), items = listOf(ChatItem.User("attached", "Can you check this?", attachments = listOf(Attachment("file", "sleep.pdf", "application/pdf", 12000))))), ConnectionState.Connected("x"), null, ChatActions())
            }
        }
        render("attachment-preview", 400, 800) {
            HarnessTheme(dark = true) { ChatContent(state.copy(preview = AttachmentPreview(Attachment("notes", "notes.md", "text/plain", 40), kotlin.io.encoding.Base64.encode("# Notes\n\nSome **bold** text\n\n- one\n- two".encodeToByteArray()))), ConnectionState.Connected("x"), null, ChatActions()) }
        }
        render("composer-light", 390, 152) {
            HarnessTheme(dark = false) {
                Column(Modifier.fillMaxSize().background(color(backgroundColor)).padding(12.dp)) {
                    kinuvo.ui.design.Composer(false, {}, {}, placeholder = "Chat with Kinuvo",
                        controls = { ComposerSettings(SessionSettings(), defaults, ModelsCatalog(listOf(ProviderModels("codex", "ChatGPT / Codex", listOf(ModelInfo(defaults.model))))), {}) })
                }
            }
        }
        render("chat-session-settings", 390, 844) { HarnessTheme(dark = false) { ChatContent(state.copy(running = false, defaults = defaults, models = ModelsCatalog(listOf(ProviderModels("codex", "ChatGPT / Codex", listOf(ModelInfo("gpt-5.6-sol"), ModelInfo("gpt-5.6-mini"))))), settings = SessionSettings(reasoning = "high")), ConnectionState.Connected("x"), null, ChatActions()) } }
        render("chat-narrow", 420, 860) { HarnessTheme(dark = false) { ChatContent(state.copy(running = false), ConnectionState.Connected("x"), null, ChatActions()) } }
        render("chat-phone", 400, 860) {
            HarnessTheme(dark = true) {
                androidx.compose.runtime.CompositionLocalProvider(com.composables.ui.theme.LocalInteractionMode provides com.composables.ui.theme.InteractionMode.Touch) {
                    ChatContent(state.copy(running = false), ConnectionState.Connected("x"), null, ChatActions(), onToggleShell = {})
                }
            }
        }
        render("chat-empty", 1200, 700) { HarnessTheme(dark = true) { ChatContent(state.copy(items = emptyList(), running = false), ConnectionState.Connected("x"), usage, ChatActions()) } }
        render("agent-long-name", 1400, 900) { HarnessTheme(dark = true) { AgentContent(settingsAt(Route.Settings("main")).copy(agents = listOf(AgentInfo("main", "House Agent for the Family", "host"))), {}) } }
        render("agent-long-name-phone", 390, 1300) { HarnessTheme(dark = true) { AgentContent(settingsAt(Route.Settings("main")).copy(agents = listOf(AgentInfo("main", "House Agent for the Family", "host"))), {}) } }
        render("agent-wide", 1400, 900) { HarnessTheme(dark = true) { AgentContent(settingsAt(Route.Settings("main")), {}) } }
        render("agent-empty-light", 1400, 900) { HarnessTheme(dark = false) { AgentContent(settingsAt(Route.Settings("main")).let { it.copy(state = it.state.copy(routines = emptyList())) }, {}) } }
        render("agent-phone", 390, 1300) { HarnessTheme(dark = true) { AgentContent(settingsAt(Route.Settings("main")), {}, onBack = {}) } }
        render("settings-overview", 1600, 900) { HarnessTheme(dark = true) { SettingsContent(settingsAt(Route.Settings("main", SettingsSection.Overview)), SettingsActions()) } }
        render("settings-wide", 1280, 860) { HarnessTheme(dark = false) { SettingsContent(settings, SettingsActions()) } }
        render("settings-medium-dark", 900, 760) { HarnessTheme(dark = true) { SettingsContent(settingsAt(Route.Settings("main", SettingsSection.Routines)), SettingsActions()) } }
        render("settings-narrow-detail", 420, 860) { HarnessTheme(dark = false) { SettingsContent(settingsAt(Route.Settings("main", SettingsSection.Routines, "heartbeat")), SettingsActions()) } }
        render("settings-narrow-sections", 420, 860) { HarnessTheme(dark = false) { SettingsContent(settingsAt(Route.Settings("main")), SettingsActions()) } }
        render("settings-providers", 1280, 760) {
            val providers = listOf(
                kinuvo.protocol.ProviderInfo("codex", "ChatGPT / Codex", kinuvo.protocol.ProviderAccount(false)),
                kinuvo.protocol.ProviderInfo("apple", "Apple · On device"),
                ProviderInfo("openai", "OpenAI API", apiKey = ProviderApiKeyStatus(false, "macOS Keychain")),
            )
            val ui = settingsAt(Route.Settings("main", SettingsSection.Providers))
            HarnessTheme(dark = false) { SettingsContent(ui.copy(state = ui.state.copy(providers = providers, deviceCode = "codex" to kinuvo.protocol.DeviceCode("https://auth.openai.com/codex/device", "ABCD-1234", 0))), SettingsActions()) }
        }
        render("settings-provider-key", 900, 850) {
            val ui = settingsAt(Route.Settings("main", SettingsSection.Providers))
            val key = ProviderApiKeyStatus(true, "~/.kinuvo/credentials/openai-reference (reference only)", true, "\${pass://Kinuvo/OpenAI/api key}")
            val state = ui.state.copy(providers = listOf(ProviderInfo("openai", "OpenAI API", apiKey = key)), secrets = listOf(SecretProviderStatus("pass", "Proton Pass", installed = true, connected = true)))
            HarnessTheme(dark = false) { SettingsContent(ui.copy(state = state), SettingsActions()) }
        }
        render("settings-agent", 1280, 1000) { HarnessTheme(dark = false) { SettingsContent(settingsAt(Route.Settings("main", SettingsSection.Agent)), SettingsActions()) } }
        render("settings-agent-phone", 420, 1100) { HarnessTheme(dark = true) { SettingsContent(settingsAt(Route.Settings("main", SettingsSection.Agent)), SettingsActions()) } }
        render("settings-agent-tools", 1280, 1100) { HarnessTheme(dark = false) { SettingsContent(settingsAt(Route.Settings("main", SettingsSection.Tools)), SettingsActions()) } }
        render("settings-agent-workspace-phone", 420, 900) { HarnessTheme(dark = true) { SettingsContent(settingsAt(Route.Settings("main", SettingsSection.Workspace)), SettingsActions()) } }
        render("settings-new-agent", 900, 700) { HarnessTheme(dark = true) { SettingsContent(settingsAt(Route.Settings("main", SettingsSection.Agent, editing = true)), SettingsActions()) } }
        render("settings-routine-form", 1280, 900) { HarnessTheme(dark = false) { SettingsContent(settingsAt(Route.Settings("main", SettingsSection.Routines, "r1", editing = true)), SettingsActions()) } }
        render("settings-skills", 1280, 860) { HarnessTheme(dark = false) { SettingsContent(settingsAt(Route.Settings("main", SettingsSection.Skills, "Agent/weekly-review")), SettingsActions()) } }
        render("settings-skill-new", 420, 860) { HarnessTheme(dark = true) { SettingsContent(settingsAt(Route.Settings("main", SettingsSection.Skills, editing = true)), SettingsActions()) } }
        render("settings-mcp", 1280, 860) { HarnessTheme(dark = true) { SettingsContent(settingsAt(Route.Settings("main", SettingsSection.Mcp, "Agent/github")), SettingsActions()) } }
        render("settings-mcp-signin", 1280, 860) {
            val signingIn = settingsAt(Route.Settings("main", SettingsSection.Mcp, "Agent/linear"))
            HarnessTheme(dark = false) { SettingsContent(signingIn.copy(state = signingIn.state.copy(mcpTest = null, signInUrl = "https://mcp.linear.app/authorize?response_type=code&client_id=kinuvo&state=…")), SettingsActions()) }
        }
        render("settings-mcp-form", 1280, 960) { HarnessTheme(dark = false) { SettingsContent(settingsAt(Route.Settings("main", SettingsSection.Mcp, "Shared/fs", editing = true)), SettingsActions()) } }
        render("chat-routine-turn", 1000, 600) {
            HarnessTheme(dark = false) {
                ChatContent(state.copy(items = listOf(ChatItem.User("u1", "Summarize my unread email.", "Morning brief"), ChatItem.Assistant("a1", "Three new emails; nothing urgent."))), ConnectionState.Connected("x"), null, ChatActions())
            }
        }
        val form = AgentEvent.QuestionsAsked("q1", "c1", "Plan the Lisbon trip", listOf(
            Question("dates", "When do you leave?", QuestionKind.Date, required = true),
            Question("budget", "Budget", QuestionKind.Single, listOf("Under €300", "€300–600", "Flexible"), default = listOf("Under €300")),
            Question("extras", "Also book", QuestionKind.Multi, listOf("Hotel", "Airport transfer", "Restaurant"), default = listOf("Hotel")),
            Question("window", "Window seat?", QuestionKind.YesNo),
            Question("notes", "Anything else?", QuestionKind.LongText, placeholder = "Dietary needs, people coming…"),
        ))
        val asking = """{"title":"Plan the Lisbon trip","questions":[{"id":"budget","label":"Budget","kind":"single","options":["Under €300","Flexible"]},{"id":"extras","label":"Also book","kind":"multi","options":["Hotel","Car"]}]}"""
        val cards = listOf(
            ChatItem.ToolCall("t1", "c0", "ask_user", asking, """{"budget":"Flexible","extras":["Hotel","Car"]}"""),
            ChatItem.ToolCall("t2", "c2", "artifact", """{"action":"create","title":"Lisbon options"}""", """{"id":"lisbon-options-3f2a","title":"Lisbon options","type":"html"}"""),
            ChatItem.ToolCall("t3", "c1", "ask_user", "{}", questions = form),
        )
        render("chat-ask-user", 1000, 1100) { HarnessTheme(dark = false) { ChatContent(state.copy(items = cards), ConnectionState.Connected("x"), null, ChatActions()) } }
        render("chat-ask-user-dark", 420, 1200) { HarnessTheme(dark = true) { ChatContent(state.copy(items = cards), ConnectionState.Connected("x"), null, ChatActions()) } }
        renderSteps()
        val artifacts = ArtifactsState(
            Route.Artifacts("plan-1"),
            all = listOf(
                ArtifactInfo("plan-1", "Lisbon plan", "md", "main", "s1", 1_790_000_000_000L, 1_790_000_300_000L),
                ArtifactInfo("flights-2", "Flight prices", "csv", "research", null, 1_789_990_000_000L, 1_789_990_000_000L),
                ArtifactInfo("chart-3", "Spending chart", "html", "main", null, 1_789_900_000_000L, 1_789_900_000_000L),
            ),
            open = ArtifactData(ArtifactInfo("plan-1", "Lisbon plan", "md", "main", "s1", 1_790_000_000_000L, 1_790_000_300_000L), "# Lisbon\n\n- Flights under €300\n- Hotel near Alfama"),
        )
        val team = listOf(agent, AgentInfo("research", "Researcher", "container"))
        render("artifacts-wide", 1280, 800) { HarnessTheme(dark = false) { ArtifactsContent(artifacts, team, 1_790_000_600_000L, ArtifactsActions()) } }
        render("artifacts-narrow-list", 420, 860) { HarnessTheme(dark = true) { ArtifactsContent(artifacts.copy(route = Route.Artifacts()), team, 1_790_000_600_000L, ArtifactsActions(onBack = {})) } }
        startKinuvo(module { single<Preferences> { object : Preferences {
            private val values = mutableMapOf<String, String>()
            override fun get(key: String) = values[key]
            override fun set(key: String, value: String?) { if (value == null) values.remove(key) else values[key] = value }
        } } })
        try {
            render("connect", 900, 700) { KinuvoApp() }
        } finally {
            stopKoin()
        }
    }

    /** The rail of Agents and the shown Agent's column, beside a Session and alone as the phone's home screen. */
    @Test
    fun renderShell() {
        val running = SessionInfo("x-1", "research", "Compare flights", SessionStatus.Running)
        val shellState = ShellState(agents, agent, mapOf("main" to SessionList(sessions), "research" to SessionList(listOf(running))),
            finished = setOf(sessions[3].id), view = Route.Chat("main", sessions.first().id), servers = servers,
            routines = mapOf("main" to listOf(Routine("r2", "main", "Inbox triage", "Triage my inbox", Trigger.Every(60), "UTC", sessionId = "20250101-090000-a7"))))
        listOf(false, true).forEach { dark ->
            render("shell-wide-${if (dark) "dark" else "light"}", 1400, 900) {
                HarnessTheme(dark = dark) {
                    androidx.compose.foundation.layout.Row {
                        Shell(shellState, ShellActions(), ShellChrome(androidx.compose.runtime.mutableStateOf(true)))
                        com.composables.ui.components.VerticalSeparator()
                        ChatContent(state, ConnectionState.Connected("x"), usage, ChatActions(), wide = true, onToggleShell = {})
                    }
                }
            }
        }
        val embedded = ServerCapabilities("device")
        val blocked = SessionCapabilities(server = embedded, chat = CapabilityState.unavailable("On-device chat needs a physical Apple Intelligence-capable device."))
        render("shell-embedded", 1400, 900) {
            HarnessTheme(dark = false) {
                androidx.compose.foundation.layout.Row {
                    Shell(shellState.copy(capabilities = embedded), ShellActions(), ShellChrome(androidx.compose.runtime.mutableStateOf(true)))
                    com.composables.ui.components.VerticalSeparator()
                    ChatContent(state.copy(running = false, defaults = AgentSettings("apple", "none", "default", "host"), capabilities = blocked), ConnectionState.Connected("x"), null, ChatActions(), wide = true, onToggleShell = {})
                }
            }
        }
        render("agent-embedded", 1400, 900) { HarnessTheme(dark = true) { AgentContent(settingsAt(Route.Settings("main")).copy(capabilities = embedded), {}) } }
        render("shell-phone", 390, 844) {
            HarnessTheme(dark = true) {
                kinuvo.ui.design.Reveal(true, {}, behind = { Shell(shellState, ShellActions(), ShellChrome(androidx.compose.runtime.mutableStateOf(true)), Modifier.fillMaxSize(), fill = true) }) {
                    ChatContent(state.copy(running = false), ConnectionState.Connected("x"), null, ChatActions(), wide = false, onToggleShell = {})
                }
            }
        }
        render("quick-chat", 390, 844) {
            val draft = androidx.compose.foundation.text.input.TextFieldState("Remind me to call the dentist tomorrow at 10")
            HarnessTheme(dark = true) {
                androidx.compose.foundation.layout.Box {
                    androidx.compose.foundation.layout.Box(Modifier.blur(16.dp)) {
                        kinuvo.ui.design.Reveal(true, {}, behind = { Shell(shellState, ShellActions(), ShellChrome(androidx.compose.runtime.mutableStateOf(true)), Modifier.fillMaxSize(), fill = true) }) {
                            ChatContent(state.copy(running = false), ConnectionState.Connected("x"), null, ChatActions(), wide = false)
                        }
                    }
                    QuickChat(agents, agent, draft, {}, { _, _, _ -> true }, {})
                }
            }
        }
        render("shell-collapsed", 1400, 800) {
            val chrome = ShellChrome(androidx.compose.runtime.mutableStateOf(true)).apply { collapsed = ShellSection.entries.toSet() }
            HarnessTheme(dark = true) { Shell(shellState, ShellActions(), chrome) }
        }
        render("shell-board", 1400, 800) {
            HarnessTheme(dark = false) { Shell(shellState.copy(view = Route.Board), ShellActions(), ShellChrome(androidx.compose.runtime.mutableStateOf(true))) }
        }
    }

    /** Tool calls and reasoning grouped into compact rows, at iPhone width. */
    private fun renderSteps() {
        val think = ChatItem.Reasoning("r1", "Checking git status and open PRs before summarising.")
        val status = ChatItem.ToolCall("t1", "t1", "shell", """{"command":"git status --short"}""", result = " M core/src/Harness.kt", identity = ToolIdentity(PluginInstanceId("kinuvo.exec"), "shell"))
        val prs = ChatItem.ToolCall("t2", "t2", "github__list_pull_requests", """{"repo":"harness"}""", result = "[]", identity = ToolIdentity(PluginInstanceId("kinuvo.mcp", "shared/github"), "list_pull_requests"))
        val read = ChatItem.ToolCall("t3", "t3", "read_file", """{"path":"DESIGN.md"}""", result = "# Kinuvo")
        val failed = ChatItem.ToolCall("t4", "t4", "shell", """{"command":"./kotlin test -m core"}""", result = "exit 1: 2 tests failed", isError = true, identity = ToolIdentity(PluginInstanceId("kinuvo.exec"), "shell"))
        val fetch = ChatItem.ToolCall("t5", "t5", "web_fetch", """{"url":"https://kotlinlang.org"}""", result = "ok")
        val push = ChatItem.ToolCall(
            "t6", "t6", "shell", """{"command":"git push origin tier1"}""", identity = ToolIdentity(PluginInstanceId("kinuvo.exec"), "shell"),
            approval = AgentEvent.ApprovalRequested("a1", "t6", "shell", """{"command":"git push origin tier1"}""", "git push", Risk.High, "Pushes to a remote"),
        )
        val items = listOf(
            ChatItem.User("u1", "What's uncommitted, and are the tests green?"),
            think, status, prs, read,
            ChatItem.Assistant("a1", "One modified file, `Harness.kt`, and no open PRs. Running the tests next."),
            failed, fetch,
            ChatItem.Assistant("a2", "Two tests fail in `core`. Want me to push the branch anyway?"),
            ChatItem.Reasoning("r2", "The user asked to push; that needs approval."), push,
        )
        val phone = state.copy(items = items, running = false)
        render("chat-steps-phone", 390, 844) { HarnessTheme(dark = false) { ChatContent(phone, ConnectionState.Connected("x"), null, ChatActions()) } }
        val running = state.copy(items = items.take(4) + status.copy(id = "t7", callId = "t7", args = """{"command":"git diff --stat HEAD~3"}""", result = null), running = true)
        render("chat-steps-running-dark", 390, 600) { HarnessTheme(dark = true) { ChatContent(running, ConnectionState.Connected("x"), null, ChatActions()) } }
        render("chat-steps-expanded", 390, 520) {
            HarnessTheme(dark = false) {
                Column(Modifier.fillMaxSize().background(color(backgroundColor)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    StepsView(ChatRow.Steps(listOf(think, status, prs, read)), active = false, initiallyExpanded = true)
                    StepsView(ChatRow.Steps(listOf(failed, fetch)), active = false, initiallyExpanded = true)
                }
            }
        }
    }

    private val board = BoardUi(
        BoardState(
            BoardData(
                lists = listOf(TaskListInfo("l1", "Errands", "main"), TaskListInfo("l2", "Travel", "research")),
                tasks = listOf(
                    TaskInfo("t1", "l1", "Errands", "Buy a birthday present for Ana", due = "2026-10-08", assignee = "main"),
                    TaskInfo("t2", "l2", "Travel", "Book flights to Lisbon", "Under €300, window seat.", "2026-10-20", TaskStatus.InProgress, "research", listOf("s1")),
                    TaskInfo("t3", "l2", "Travel", "Renew passport", "Kinuvo: Form drafted. Needs a new photo.", "2026-11-20", TaskStatus.Waiting, "research", listOf("s2")),
                    TaskInfo("t4", "l1", "Errands", "Cancel the gym membership"),
                    TaskInfo("t5", "l1", "Errands", "Pay the electricity bill", status = TaskStatus.Done, assignee = "main"),
                ),
            ),
            showDone = true,
        ),
        listOf(agent, AgentInfo("research", "Researcher", "container")),
    )

    private val ana = Note(
        "People/Ana García", "person", "Ana García",
        mapOf("relationship" to "landlord", "email" to "ana@example.com"),
        "Met at the viewing. Prefers WhatsApp. Landlord of [[Places/Flat 3B|Flat 3B]].\n\n## Timeline\n\n- 2026-09-02: Rent is due on the 1st\n- 2026-09-28: Fixing the boiler next week",
    )
    private val knowledge = KnowledgeUi(
        KnowledgeState(
            Route.Knowledge(ana.ref),
            changes = listOf(
                KnowledgeChange("k1", 1_790_000_000_000L, "Ana García: 2026-09-28: Fixing the boiler next week", ana.ref, ana.title, "main", "s1", scope = "markdown:test", undo = kotlinx.serialization.json.JsonNull),
                KnowledgeChange("k2", 1_789_990_000_000L, "Created Lisbon trip", "Projects/Lisbon trip", "Lisbon trip", "research", scope = "markdown:test", undo = kotlinx.serialization.json.JsonNull),
                KnowledgeChange("k3", 1_789_900_000_000L, "2026-09-29: Called Mum", "Journal/2026-09-29", "2026-09-29", "main", undone = true, scope = "markdown:test", undo = kotlinx.serialization.json.JsonNull),
            ),
            note = ana,
        ),
        listOf(agent),
        now = 1_790_000_600_000L,
    )

    @Test
    fun renderReflectionReview() {
        val args = kotlinx.serialization.json.buildJsonObject {
            put("action", kotlinx.serialization.json.JsonPrimitive("update"))
            put("ref", kotlinx.serialization.json.JsonPrimitive("project-1"))
            put("append", kotlinx.serialization.json.JsonPrimitive("The release is planned for Friday."))
            put("under", kotlinx.serialization.json.JsonPrimitive("Timeline"))
        }
        val proposal = ReflectionProposal("p1", "knowledge", args, "Keep the confirmed release date with the project.",
            "user-0: release on Friday", note = Note("project-1", "project", "Kinuvo", body = "A personal AI assistant."), scope = "markdown")
        val skillArgs = kotlinx.serialization.json.buildJsonObject {
            put("action", kotlinx.serialization.json.JsonPrimitive("create"))
            put("name", kotlinx.serialization.json.JsonPrimitive("release-check"))
            put("content", kotlinx.serialization.json.JsonPrimitive("---\nname: release-check\ndescription: Verify a release\n---\nRun the release checks before publishing."))
        }
        for (dark in listOf(false, true)) {
            render("reflection-memory-${if (dark) "dark" else "light"}", 420, 650) {
                HarnessTheme(dark = dark) {
                    Column(Modifier.fillMaxSize().background(color(backgroundColor)).padding(16.dp)) {
                        ReflectionView(ChatItem.Reflection("memory", ReflectionResult(memory = 1, userNotes = 2, memoryChanges = listOf(
                            ReflectionMemoryChange("memory", "", "- Give concrete examples\n"),
                            ReflectionMemoryChange("user", "- Lives in Cork\n- Likes tea\n", "- Lives in Dublin\n- Likes tea\n"),
                            ReflectionMemoryChange("user", "- Lives in Dublin\n- Likes tea\n", "- Lives in Dublin\n"),
                        ))), initiallyExpanded = true)
                    }
                }
            }
            render("reflection-review-${if (dark) "dark" else "light"}", 420, 650) {
                HarnessTheme(dark = dark) {
                    Column(Modifier.fillMaxSize().background(color(backgroundColor)).padding(16.dp)) {
                        ReflectionView(ChatItem.Reflection("r1", ReflectionResult(memory = 1, proposals = listOf(proposal))), initiallyExpanded = true)
                    }
                }
            }
            render("reflection-skill-${if (dark) "dark" else "light"}", 420, 650) {
                HarnessTheme(dark = dark) {
                    Column(Modifier.fillMaxSize().background(color(backgroundColor)).padding(16.dp)) {
                        ReflectionView(ChatItem.Reflection("r2", ReflectionResult(proposals = listOf(proposal.copy(id = "p2", tool = "skill_manage", args = skillArgs,
                            reason = "Reuse the verified release workflow.", evidence = "check-1: release checks passed", note = null, scope = null)))), initiallyExpanded = true)
                    }
                }
            }
            render("reflection-skill-edit-${if (dark) "dark" else "light"}", 420, 650) {
                val before = "---\nname: release-check\ndescription: Verify a release\n---\nRun the release checks before publishing."
                val edit = kotlinx.serialization.json.buildJsonObject {
                    put("action", kotlinx.serialization.json.JsonPrimitive("edit"))
                    put("name", kotlinx.serialization.json.JsonPrimitive("release-check"))
                    put("content", kotlinx.serialization.json.JsonPrimitive("$before\n\n## Pitfalls\n- Tag after the changelog is merged."))
                }
                HarnessTheme(dark = dark) {
                    Column(Modifier.fillMaxSize().background(color(backgroundColor)).padding(16.dp)) {
                        ReflectionView(ChatItem.Reflection("r3", ReflectionResult(proposals = listOf(proposal.copy(id = "p3", tool = "skill_manage", args = edit, skillBefore = before,
                            reason = "Tagging early broke the release.", evidence = "user-4: the tag went out before the changelog merged, so the release notes were empty and we had to redo it", note = null, scope = null)))), initiallyExpanded = true)
                    }
                }
            }
        }
    }

    @Test
    fun renderWideTable() {
        render("markdown-wide-table", 390, 260) {
            HarnessTheme(dark = true) {
                Column(Modifier.fillMaxSize().background(color(backgroundColor)).padding(16.dp)) {
                    Markdown("""
                        | Night | Asleep | Deep | REM | Awake | Score |
                        |---|---|---|---|---|---|
                        | Mon 28 Sep | 7h 12m | 1h 20m | 1h 45m | 18m | 82 |
                        | Tue 29 Sep | 6h 40m | 1h 05m | 1h 30m | 25m | 74 |
                    """.trimIndent())
                }
            }
        }
    }

    @Test
    fun renderComputerControls() {
        for (dark in listOf(false, true)) for (platform in listOf("Android", "macOS", "Windows"))
            render("computer-controls-${platform.lowercase()}-${if (dark) "dark" else "light"}", 400, if (platform == "Android") 170 else 330) {
                HarnessTheme(dark = dark) {
                    Column(Modifier.fillMaxSize().background(color(backgroundColor)).padding(12.dp)) {
                        kinuvo.ui.computer.ComputerControls(platform, {}, {})
                    }
                }
            }
    }

    @Test
    fun renderMarkdownTypography() {
        for (dark in listOf(false, true)) render("markdown-inter-${if (dark) "dark" else "light"}", 620, 440) {
            HarnessTheme(dark = dark) {
                Column(Modifier.fillMaxSize().background(color(backgroundColor)).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("Markdown typography", fontSize = 14.sp)
                    Markdown("""Here's what I know from what you've told me:

- Your name is Fernando.
- Your favourite colour is teal.
- You're working on Kinuvo, a self-hosted personal AI assistant. You prefer small, concrete examples.

I checked your knowledge base just now and didn't find a profile note for you.

**Bold emphasis**, *italic text* and ***bold italic*** still work.

| Style | Purpose |
| --- | --- |
| Regular | Paragraph text |
| Bold | Explicit emphasis |
""")
                }
            }
        }
    }

    @Test
    fun renderVoiceChat() {
        val reply = "Hello Fernando! I'm Kinuvo, your assistant. We're chatting over voice right now, so think of me as someone you can talk things through with. How can I help you today?"
        val items = listOf(ChatItem.User("u", "Hello. Who are you?"), ChatItem.Assistant("a", reply))
        val defaults = AgentSettings("gpt-5.6-sol", "medium", "default", "host")
        val voice = state.copy(items = items, running = false, defaults = defaults, models = ModelsCatalog(listOf(ProviderModels("codex", "ChatGPT / Codex", listOf(ModelInfo("gpt-5.6-sol"))))))
        val speaking = VoiceChatState(VoicePhase.Speaking, spoken = mapOf("a" to reply.indexOf("so think")))
        render("voice-speaking-dark", 390, 844) { HarnessTheme(dark = true) { ChatContent(voice.copy(voice = speaking), ConnectionState.Connected("x"), null, ChatActions()) } }
        render("voice-paused-light", 390, 844) { HarnessTheme(dark = false) { ChatContent(voice.copy(voice = VoiceChatState(VoicePhase.Listening, paused = true)), ConnectionState.Connected("x"), null, ChatActions()) } }
        render("voice-listening-dark", 390, 844) {
            HarnessTheme(dark = true) { ChatContent(voice.copy(voice = VoiceChatState(VoicePhase.Listening, heard = "What's on my calendar")), ConnectionState.Connected("x"), null, ChatActions()) }
        }
    }

    @Test
    fun renderMeasurementOnlySession() {
        val metrics = RequestMetrics("m1", 0, RequestKind.Chat, "codex", "model", "medium", 100)
        val items = listOf(ChatItem.Measurement("m1", metrics))
        kotlin.test.assertEquals(emptyList(), items.rows())
        for (running in listOf(false, true)) render("chat-measurement-only-$running", 390, 844) {
            HarnessTheme(dark = false) {
                ChatContent(state.copy(items = items, running = running, loaded = true), ConnectionState.Connected("x"), null, ChatActions())
            }
        }
    }

    @Test
    fun renderRequestMeasurements() {
        val metrics = kinuvo.protocol.RequestMetrics("m1", 123, kinuvo.protocol.RequestKind.Chat, "codex", "gpt-5.6-sol", "medium", 2340, 320, 12000, 560, 10240)
        for (dark in listOf(false, true)) render("request-metrics-${if (dark) "dark" else "light"}", 420, 640) {
            HarnessTheme(dark = dark) {
                Column(Modifier.fillMaxSize().background(color(backgroundColor)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ChatStatisticsContent(usage, listOf(
                        metrics,
                        metrics.copy(id = "m2", kind = kinuvo.protocol.RequestKind.Reflection, cachedTokens = 0),
                        metrics.copy(id = "m3", kind = kinuvo.protocol.RequestKind.Compaction, inputTokens = null, outputTokens = null, cachedTokens = null, success = false),
                    ))
                }
            }
        }
        render("statistics-empty", 390, 400) {
            HarnessTheme(dark = false) { ChatStatisticsContent(null, emptyList()) }
        }
        render("statistics-menu", 420, 640) {
            HarnessTheme(dark = false) {
                androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().background(color(backgroundColor)), contentAlignment = androidx.compose.ui.Alignment.TopEnd) {
                    ChatStatistics(usage, listOf(metrics), "session", initiallyExpanded = true)
                }
            }
        }
    }

    @Test
    fun renderReflection() {
        for (dark in listOf(false, true)) render("reflection-${if (dark) "dark" else "light"}", 420, 480) {
            HarnessTheme(dark = dark) {
                Column(Modifier.fillMaxSize().background(color(backgroundColor)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ReflectionView(ChatItem.Reflection("running"))
                    ReflectionView(ChatItem.Reflection("done", ReflectionResult(knowledge = 2, memory = 1, userNotes = 1)), initiallyExpanded = true)
                    ReflectionView(ChatItem.Reflection("empty", ReflectionResult()))
                    ReflectionView(ChatItem.Reflection("failed", ReflectionResult(error = "Knowledge provider is unavailable.")), initiallyExpanded = true)
                }
            }
        }
    }

    @Test
    fun renderBoardAndKnowledge() {
        render("board-wide", 1280, 800) { HarnessTheme(dark = false) { BoardContent(board, BoardActions()) } }
        render("board-adding-dark", 1280, 800) { HarnessTheme(dark = true) { BoardContent(board.copy(state = board.state.copy(adding = true, showDone = false)), BoardActions()) } }
        render("board-narrow", 420, 860) { HarnessTheme(dark = false) { BoardContent(board, BoardActions(onBack = {})) } }
        render("knowledge-wide", 1280, 800) { HarnessTheme(dark = false) { KnowledgeContent(knowledge, KnowledgeActions()) } }
        render("knowledge-narrow-list", 420, 860) {
            HarnessTheme(dark = true) { KnowledgeContent(knowledge.copy(state = knowledge.state.copy(route = Route.Knowledge(), query = "ana", results = listOf(NoteSummary(ana.ref, ana.type, ana.title)))), KnowledgeActions(onBack = {})) }
        }
        render("knowledge-narrow-note", 420, 860) { HarnessTheme(dark = false) { KnowledgeContent(knowledge, KnowledgeActions(onBack = {})) } }
        val tasksSettings = settings.copy(state = settings.state.copy(
            route = Route.Settings("main", SettingsSection.Tasks),
            tasks = TasksSetup(TasksSettings("reminders", mapOf("Errands" to "main", "Travel" to "")), listOf("local", "reminders")),
            providerLists = mapOf("reminders" to listOf(TaskListInfo("a", "Errands"), TaskListInfo("b", "Travel"), TaskListInfo("c", "Groceries"))),
        ))
        render("settings-tasks", 1280, 860) { HarnessTheme(dark = false) { SettingsContent(tasksSettings, SettingsActions()) } }
        val deniedTasks = tasksSettings.copy(state = tasksSettings.state.copy(
            providerLists = emptyMap(),
            errors = listOf("Reminders access is denied for T3 Code (Nightly). Authorizing remindctl in another terminal does not grant this Server access. Enable T3 Code (Nightly) in System Settings → Privacy & Security → Reminders, then click Request access / Retry. If the app isn’t listed, launch the Server from the terminal where remindctl is already authorized."),
        ))
        render("settings-tasks-denied", 900, 1100) { HarnessTheme(dark = true) { SettingsContent(deniedTasks, SettingsActions()) } }
        render("settings-tasks-denied-narrow", 420, 1400) { HarnessTheme(dark = true) { SettingsContent(deniedTasks, SettingsActions(onBack = {})) } }

        val knowledgeSettings = settings.copy(state = settings.state.copy(
            route = Route.Settings("main", SettingsSection.Knowledge),
            knowledge = KnowledgeSetup(KnowledgeSettings("~/Vault/Kinuvo"), "/Users/me/Vault/Kinuvo"),
        ))
        render("settings-knowledge-failed", 420, 860) {
            HarnessTheme(dark = false) { SettingsContent(knowledgeSettings.copy(state = knowledgeSettings.state.copy(knowledge = null, errors = listOf("Not connected"))), SettingsActions(onBack = {})) }
        }
        val ntfy = SettingsProvider("ntfy", schema = SettingsSchema(listOf(
            SettingsField("topic", "Topic", description = "Install the ntfy app on your phone and subscribe to this topic. Anyone who knows the topic can read it on a public server, so make it long and random.",
                required = true, randomPrefix = "kinuvo-", link = SettingsFieldLink("url")),
            SettingsField("url", "Server URL", SettingsFieldType.Url, "Only for a self-hosted ntfy.", defaultValue = "https://ntfy.sh"),
            SettingsField("token", "Access token", description = "Only for protected topics.", placeholder = "tk_…", sensitive = true),
        )))
        val firecrawl = SettingsProvider("firecrawl", "Firecrawl", SettingsSchema(listOf(
            SettingsField("apiKey", "API key", description = "From firecrawl.dev → API keys.", placeholder = "fc-…", required = true, sensitive = true),
            SettingsField("url", "Server URL", SettingsFieldType.Url, "Only for a self-hosted Firecrawl.", defaultValue = "https://api.firecrawl.dev"),
        )))
        val notifySettings = settings.copy(state = settings.state.copy(
            route = Route.Settings("main", SettingsSection.Notifications),
            services = mapOf(ServiceKind.Notifications to ServiceSetup(ServiceKind.Notifications, ServiceSettings("ntfy", mapOf("topic" to "kinuvo-7fk2m9qx4hw8")),
                listOf(ntfy))),
            serviceTest = ServiceTestResult("Sent"),
        ))
        render("settings-notifications-narrow", 390, 1000) { HarnessTheme(dark = false) { SettingsContent(notifySettings, SettingsActions(onBack = {})) } }
        val searchSettings = settings.copy(state = settings.state.copy(
            route = Route.Settings("main", SettingsSection.Search),
            services = mapOf(ServiceKind.Search to ServiceSetup(ServiceKind.Search, ServiceSettings("firecrawl", mapOf("apiKey" to KnowledgeSettings.SECRET)),
                listOf(firecrawl), setOf("apiKey"))),
        ))
        render("settings-search", 900, 760) { HarnessTheme(dark = true) { SettingsContent(searchSettings, SettingsActions()) } }
        val custom = SettingsProvider("custom", "Custom search", SettingsSchema(listOf(
            SettingsField("endpoint", "Endpoint", SettingsFieldType.Url, defaultValue = "https://search.example.org"),
            SettingsField("enabled", "Include summaries", SettingsFieldType.Boolean, defaultValue = "true"),
            SettingsField("format", "Format", SettingsFieldType.Choice, defaultValue = "Markdown", choices = listOf("Markdown", "Text")),
            SettingsField("limit", "Result limit", SettingsFieldType.Integer, defaultValue = "10"),
        )))
        val customSettings = searchSettings.copy(state = searchSettings.state.copy(services = mapOf(ServiceKind.Search to
            ServiceSetup(ServiceKind.Search, ServiceSettings("custom"), listOf(custom)))))
        render("settings-custom-provider", 420, 1000) { HarnessTheme(dark = false) { SettingsContent(customSettings, SettingsActions(onBack = {})) } }
        val unsupported = customSettings.copy(state = customSettings.state.copy(services = mapOf(ServiceKind.Search to
            ServiceSetup(ServiceKind.Search, ServiceSettings("custom"), listOf(custom.copy(schema = SettingsSchema(
                listOf(SettingsField("timeout", "Timeout", SettingsFieldType("Duration"))), version = 2)))))))
        render("settings-provider-update-required", 390, 700) { HarnessTheme(dark = true) { SettingsContent(unsupported, SettingsActions(onBack = {})) } }
        render("settings-reflection-prompt", 900, 1200) { HarnessTheme(dark = false) { SettingsContent(knowledgeSettings, SettingsActions()) } }
        render("settings-reflection-prompt-narrow", 420, 1400) { HarnessTheme(dark = true) { SettingsContent(knowledgeSettings, SettingsActions(onBack = {})) } }
        render("settings-knowledge", 900, 760) { HarnessTheme(dark = true) { SettingsContent(knowledgeSettings, SettingsActions()) } }
        val anytypeSettings = knowledgeSettings.copy(state = knowledgeSettings.state.copy(
            knowledge = KnowledgeSetup(
                KnowledgeSettings(provider = "anytype", options = mapOf("apiKey" to KnowledgeSettings.SECRET, "space" to "my-space"),
                    conventions = mapOf("anytype" to "People are Human (profile) objects; companies are Organization.\nAdd dated facts as bullets under a Timeline heading.")),
                "/Users/me/.kinuvo/knowledge", listOf("markdown", "anytype"),
            ),
        ))
        render("settings-anytype", 900, 860) { HarnessTheme(dark = false) { SettingsContent(anytypeSettings, SettingsActions()) } }
        render("settings-anytype-narrow", 420, 860) { HarnessTheme(dark = true) { SettingsContent(anytypeSettings, SettingsActions(onBack = {})) } }

        val pass = SecretProviderStatus("pass", "Proton Pass", installed = true, tokenStorage = "macOS Keychain",
            detail = "Add an AI agent access token from Proton Pass → Settings → Access tokens")
        val secretsSettings = settings.copy(state = settings.state.copy(route = Route.Settings("main", SettingsSection.Secrets), secrets = listOf(pass)))
        render("settings-secrets", 900, 760) { HarnessTheme(dark = false) { SettingsContent(secretsSettings, SettingsActions()) } }
        val connected = pass.copy(connected = true, detail = "Signed in; 2 vaults shared with Kinuvo")
        render("settings-secrets-connected", 420, 860) {
            HarnessTheme(dark = true) { SettingsContent(secretsSettings.copy(state = secretsSettings.state.copy(secrets = listOf(connected))), SettingsActions(onBack = {})) }
        }
        val anytypeReference = anytypeSettings.copy(state = anytypeSettings.state.copy(
            secrets = listOf(connected),
            knowledge = anytypeSettings.state.knowledge!!.let { k -> k.copy(settings = k.settings.copy(options = k.settings.options + ("apiKey" to "\${pass://Kinuvo/Anytype/api key}"))) },
        ))
        val anytypeFailed = anytypeReference.copy(state = anytypeReference.state.copy(knowledgeTest = KnowledgeTestResult(error = "Anytype rejected the API key (401). Check it in Settings → Server → Knowledge.")))
        render("settings-anytype-test-failed", 900, 1000) { HarnessTheme(dark = true) { SettingsContent(anytypeFailed, SettingsActions()) } }
        render("settings-anytype-reference", 900, 860) { HarnessTheme(dark = false) { SettingsContent(anytypeReference, SettingsActions()) } }
    }

    private fun render(name: String, width: Int, height: Int, content: @Composable () -> Unit) = javax.swing.SwingUtilities.invokeAndWait {
        ImageComposeScene(width * 2, height * 2, Density(2f), content = content).use { scene ->
            repeat(5) { scene.render(it * 500_000_000L) }
            val image = scene.render(3_000_000_000L)
            File(out, "$name.png").writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        }
    }
}
