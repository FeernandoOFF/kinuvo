package kinuvo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import com.composeunstyled.TooltipHost
import com.composables.ui.theme.backgroundColor
import kinuvo.app.navigation.Route
import kinuvo.app.rooms.RoomsState
import kinuvo.app.session.*
import kinuvo.protocol.*
import kinuvo.ui.design.*
import kinuvo.ui.navigation.*
import kinuvo.ui.rooms.*
import kinuvo.ui.session.*
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test

class SessionContextScreenshotTest {
    @Test fun contextualPanelAcrossLayoutsAndThemes() = javax.swing.SwingUtilities.invokeAndWait {
        val agents = listOf(AgentInfo("iris", "Iris", "host"), AgentInfo("milo", "Milo", "container"))
        val room = Room("launch", "Launch room", agents.map { it.id })
        val session = RoomSession("review", room.id, listOf(RoomMessage("you", text = "Review the launch page."), RoomMessage("reply", "milo", "Milo", "I’ve saved the review. The action should sit closer to the introduction.")), title = "Landing-page review")
        val rooms = RoomsState(rooms = listOf(room), selected = room, sessions = listOf(session), roomSessions = mapOf(room.id to listOf(session)), view = RoomView(session))
        val scope = SessionContextScope(roomId = room.id, roomSessionId = session.id, memberAgentIds = room.members)
        val now = 1_800_000_000_000L
        val capabilities = ServerCapabilities("home", features = setOf(ServerFeature.Artifacts, ServerFeature.Routines, ServerFeature.Shell), integrations = mapOf(Integration.Exec.name to setOf("host", "container", "ssh")))
        val defaults = AgentSettings("model", "medium", "default", "container", listOf("/work"))
        val active = EffectiveSessionSettings("codex", "model", "medium", "default", "container", listOf("/work"), ContainerSettings(), null, listOf("exec"))
        val artifact = ArtifactInfo("review", "Landing-page review", "md", "milo", "private-run", now - 600_000, now - 60_000, session.id)
        val state = SessionContextState(agentId = "milo", agents = agents, details = AgentDetails(agents[1], defaults, "", ""),
            routines = listOf(Routine("morning", "milo", "Morning screen check", "Check the page and report any layout problems.", Trigger.Cron("0 9 * * MON-FRI"), "Europe/Dublin", nextRunAt = now + 3_600_000, lastRunAt = now - 3_600_000, lastStatus = RunStatus.Ok)),
            artifacts = listOf(artifact), settings = SessionSettings(exec = "host"),
            capabilities = SessionCapabilities(server = capabilities, effective = active.copy(exec = "host"), active = active), running = true)
        for (dark in listOf(false, true)) for ((width, height) in listOf(1440 to 900, 900 to 1000, 390 to 844)) for (preview in listOf(false, true)) {
            val shown = if (preview) state.copy(artifactId = artifact.id, artifact = ArtifactData(artifact, "# Landing-page review\n\nThe first screen clearly explains what someone can do.\n\n## Suggested changes\n\n- Bring the primary action closer to the introduction.\n- Keep keyboard focus visible.\n- Check the narrow layout before publishing.")) else state
            ImageComposeScene(width, height, Density(1f), content = {
                HarnessTheme(dark = dark) { TooltipHost {
                    Box(Modifier.fillMaxSize().background(color(backgroundColor))) {
                        Row(Modifier.fillMaxSize()) {
                            if (width >= 720) Shell(ShellState(agents = agents, agent = agents[1], view = Route.Rooms, rooms = rooms), ShellActions(), ShellChrome(mutableStateOf(true)))
                            Box(Modifier.weight(1f)) { RoomsContent(agents, rooms, RoomsActions(onMenu = {}), {}) }
                            if (width >= 1300) Box(Modifier.width(320.dp).fillMaxHeight()) { SessionContextContent(shown, scope, true, capabilities, now) }
                        }
                        if (width < 1300) Box(Modifier.align(androidx.compose.ui.Alignment.CenterEnd).fillMaxHeight().then(if (width < 600) Modifier.fillMaxWidth() else Modifier.width(360.dp))) {
                            SessionContextContent(shown, scope, true, capabilities, now)
                        }
                    }
                } }
            }).use { scene ->
                repeat(10) { scene.render(it * 150_000_000L) }
                File("build/screens").apply { mkdirs() }.resolve("session-context-${if (preview) "artifact-" else ""}${if (dark) "dark" else "light"}-${width}x$height.png")
                    .writeBytes(scene.render(2_000_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes)
            }
        }
    }
}
