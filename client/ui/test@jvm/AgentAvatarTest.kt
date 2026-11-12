package kinuvo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import com.composables.ui.theme.backgroundColor
import kinuvo.ui.design.color
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import kinuvo.protocol.AgentInfo
import kinuvo.protocol.ExecutionKind
import kinuvo.protocol.ExecutionReadiness
import kinuvo.ui.agent.AgentAvatar
import kinuvo.ui.design.HarnessTheme
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import javax.swing.SwingUtilities
import kotlin.test.*

class AgentAvatarTest {
    @Test fun externalOriginsRemainVisibleWhenReadyAndDescribeUnavailableHarnesses() = SwingUtilities.invokeAndWait {
        val agents = listOf(
            AgentInfo("native", "Native", "native"),
            AgentInfo("acp", "ACP", "external", executionKind = ExecutionKind.Acp),
            AgentInfo("remote", "Remote", "external", executionKind = ExecutionKind.Remote),
            AgentInfo("offline", "Offline", "external", executionKind = ExecutionKind.Acp,
                readiness = ExecutionReadiness(true, false, "Command missing", "not_found")),
        )
        ImageComposeScene(240, 64, Density(1f), content = {
            HarnessTheme(dark = true) {
                Row(Modifier.fillMaxSize().background(color(backgroundColor)), horizontalArrangement = Arrangement.spacedBy(16.dp)) { agents.forEach { AgentAvatar(it, 40.dp) } }
            }
        }).use { scene ->
            repeat(5) { scene.render(it * 500_000_000L) }
            val image = scene.render(3_000_000_000L)
            fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
            val nodes = scene.semanticsOwners.flatMap { flatten(it.unmergedRootSemanticsNode) }
            val badges = nodes.filter { it.config.getOrNull(SemanticsProperties.ContentDescription)?.any { text -> text in setOf("External harness", "Remote Server") } == true }
            assertEquals(3, badges.size)
            assertEquals(2, badges.count { it.config[SemanticsProperties.ContentDescription] == listOf("External harness") })
            assertEquals(1, badges.count { it.config[SemanticsProperties.ContentDescription] == listOf("Remote Server") })
            assertTrue(badges.all { it.boundsInRoot.top >= 24f })
            assertTrue(nodes.any { it.config.getOrNull(SemanticsProperties.StateDescription) == "Command missing" })
            File("build/screens").mkdirs()
            File("build/screens/agent-origin-badges.png").writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        }
    }
}
