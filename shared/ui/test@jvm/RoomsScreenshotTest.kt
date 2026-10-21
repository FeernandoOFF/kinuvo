package kinuvo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import com.composables.ui.theme.backgroundColor
import kinuvo.app.rooms.RoomsState
import kinuvo.protocol.*
import kinuvo.ui.design.*
import kinuvo.ui.rooms.*
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test

class RoomsScreenshotTest {
    @Test fun rooms() = javax.swing.SwingUtilities.invokeAndWait {
        val agents = listOf(AgentInfo("planner", "Planner", "local"), AgentInfo("builder", "Builder", "local"))
        val room = Room("team", "Weekend project", agents.map { it.id }, "planner")
        val messages = listOf(RoomMessage("one", text = "Help me plan a small garden."), RoomMessage("two", "planner", "Planner", "I’ll outline the plan. Builder can check the materials.", listOf("builder"), "one", "one"))
        val session = RoomSession("session", room.id, messages)
        for ((width, height) in listOf(1000 to 1100, 390 to 1200)) {
            ImageComposeScene(width, height, Density(1f), content = {
                HarnessTheme(dark = false) {
                    Box(Modifier.fillMaxSize().background(color(backgroundColor))) {
                        RoomsContent(agents, RoomsState(rooms = listOf(room), selected = room, sessions = listOf(session), view = RoomView(session), recipients = listOf("builder")), RoomsActions(onMenu = {}, onBack = {}), {})
                    }
                }
            }).use { scene ->
                repeat(10) { scene.render(it * 150_000_000L) }
                File("build/screens").apply { mkdirs() }.resolve("rooms-$width.png").writeBytes(scene.render(2_000_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes)
            }
        }
    }
}
