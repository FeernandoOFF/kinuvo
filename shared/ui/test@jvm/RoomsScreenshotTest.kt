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
import kinuvo.protocol.*
import kinuvo.ui.design.*
import kinuvo.ui.navigation.*
import kinuvo.ui.rooms.*
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test

class RoomsScreenshotTest {
    @Test fun rooms() = javax.swing.SwingUtilities.invokeAndWait {
        val agents = listOf(AgentInfo("iris", "Iris", "local"), AgentInfo("milo", "Milo", "local"), AgentInfo("sol", "Sol", "local"))
        val room = Room("launch", "Launch room", agents.map { it.id })
        val garden = Room("garden", "Garden team", listOf("iris", "sol"))
        val session = RoomSession("review", room.id, listOf(
            RoomMessage("one", text = "Can you review the landing page before we launch?"),
            RoomMessage("two", "iris", "Iris", "## A clearer first step\nKeep the introduction short, then put the primary action directly below it.\n\n- Explain what the user can do\n- Make the next step easy to find"),
            RoomMessage("three", "milo", "Milo", "I’ll check the narrow layout and keyboard navigation next.", listOf("iris")),
        ), title = "Landing-page review")
        val second = RoomSession("checklist", room.id, title = "Release checklist")
        val gardenSession = RoomSession("garden-plan", garden.id, title = "Plan the garden")
        val sessions = mapOf(room.id to listOf(session, second), garden.id to listOf(gardenSession, RoomSession("planting", garden.id, title = "Planting schedule")))
        for (dark in listOf(false, true)) for ((width, height) in listOf(1440 to 900, 900 to 1000, 390 to 844)) for (waiting in listOf(false, true)) {
            val approval = AgentEvent.ApprovalRequested("approval", "call", "exec", """{"command":"npm test"}""", risk = Risk.Medium, reason = "Check the landing page", identity = ToolIdentity(PluginInstanceId("kinuvo.exec"), "exec"))
            val progress = if (waiting) RoomProgress("milo", "private", LiveState(approval = approval)) else null
            val state = RoomsState(rooms = listOf(room, garden), selected = room, sessions = sessions.getValue(room.id), roomSessions = sessions, view = RoomView(session, running = waiting, progress = progress), recipients = listOf("milo"))
            ImageComposeScene(width, height, Density(1f), content = {
                HarnessTheme(dark = dark) { TooltipHost {
                    Row(Modifier.fillMaxSize().background(color(backgroundColor))) {
                        if (width >= 720) Shell(ShellState(agents = agents, agent = agents.first(), view = Route.Rooms, rooms = state), ShellActions(), ShellChrome(mutableStateOf(true)))
                        Box(Modifier.weight(1f)) { RoomsContent(agents, state, RoomsActions(onMenu = {}), {}) }
                    }
                } }
            }).use { scene ->
                repeat(10) { scene.render(it * 150_000_000L) }
                File("build/screens").apply { mkdirs() }.resolve("rooms-${if (waiting) "approval-" else ""}${if (dark) "dark" else "light"}-${width}x$height.png").writeBytes(scene.render(2_000_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes)
            }
        }
    }
}
