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
import kinuvo.app.session.SessionContextState
import kinuvo.app.session.SessionContextScope
import kinuvo.protocol.*
import kinuvo.ui.design.*
import kinuvo.ui.navigation.*
import kinuvo.ui.rooms.*
import kinuvo.ui.session.SessionContextContent
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test

class RoomHeaderScreenshotTest {
    @Test fun realTitlesAcrossLayouts() = javax.swing.SwingUtilities.invokeAndWait {
        val agents = listOf(AgentInfo("atlas", "Atlas", "local"), AgentInfo("pip", "Pip", "local"))
        val room = Room("macbook", "Macbook", agents.map { it.id })
        val session = RoomSession("intro", room.id, listOf(
            RoomMessage("one", text = "Hey guys, what is your name and what tools do you have available?", recipients = room.members),
            RoomMessage("two", "atlas", "Atlas", "I’m Atlas. I can help with research, your Tasks, and the files in your Workspace."),
            RoomMessage("three", "pip", "Pip", "I’m Pip. I can work with your Computer and help you build and review software."),
        ), title = "Hey guys, what is your name and what tools do yo")
        val base = RoomsState(rooms = listOf(room), selected = room, sessions = listOf(session), roomSessions = mapOf(room.id to listOf(session)), view = RoomView(session))
        for (dark in listOf(false, true)) for (width in listOf(320, 390, 820, 1280, 1440)) {
            val height = if (width < 600) 844 else 900
            val variants = if (width == 390 || width == 1440) listOf("example", "full-title", "short", "long", "mentions", "picker", "empty", "unselected", "creating") else listOf("example")
            for (variant in variants) {
                val state = when (variant) {
                    "full-title" -> base.copy(view = RoomView(session.copy(title = session.messages.first().text)))
                    "short" -> base.copy(view = RoomView(session.copy(title = "Introductions")))
                    "long" -> base.copy(selected = room.copy(name = "Macbook · Research, development and everyday planning with the team"), view = RoomView(session.copy(title = "Help me understand every tool you can use on this Macbook and how we can work together on a new project")))
                    "mentions" -> base.copy(text = "[@Atlas](trigger:room-member:atlas) [@Pip](trigger:room-member:pip) What can you do?", recipients = listOf("atlas", "pip"))
                    "picker" -> base.copy(text = "@P")
                    "empty" -> base.copy(view = null)
                    "unselected" -> RoomsState()
                    "creating" -> base.copy(creating = true, selected = null, view = null)
                    else -> base
                }
                val details = width >= 1280
                val density = if (width == 1440 || width == 320) 2f else 1f
                ImageComposeScene((width * density).toInt(), (height * density).toInt(), Density(density), content = {
                    HarnessTheme(dark = dark) { TooltipHost {
                        Row(Modifier.fillMaxSize().background(color(backgroundColor))) {
                            if (width >= 820) Shell(ShellState(agents = agents, agent = agents.first(), view = Route.Rooms, rooms = state), ShellActions(), ShellChrome(mutableStateOf(true)))
                            Box(Modifier.weight(1f)) { RoomsContent(agents, state, RoomsActions(onMenu = {}, onBack = {}, contextOpen = details), {}) }
                            if (details) Box(Modifier.width(320.dp).fillMaxHeight()) {
                                SessionContextContent(SessionContextState(agents = agents, artifacts = emptyList()), SessionContextScope(roomId = room.id, roomSessionId = session.id, memberAgentIds = room.members), true, ServerCapabilities("home"), 0L)
                            }
                        }
                    } }
                }).use { scene ->
                    repeat(10) { scene.render(it * 150_000_000L) }
                    File("build/screens/room-header").apply { mkdirs() }.resolve("$variant-${if (dark) "dark" else "light"}-$width-density$density.png")
                        .writeBytes(scene.render(2_000_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes)
                }
            }
        }
    }
}
