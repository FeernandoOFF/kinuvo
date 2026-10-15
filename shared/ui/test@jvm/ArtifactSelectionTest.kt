package kinuvo.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.asAwtTransferable
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import java.awt.datatransfer.DataFlavor
import kinuvo.app.artifacts.ArtifactsState
import kinuvo.app.navigation.Route
import kinuvo.protocol.ArtifactData
import kinuvo.protocol.ArtifactInfo
import kinuvo.ui.artifacts.ArtifactsActions
import kinuvo.ui.artifacts.ArtifactsContent
import kinuvo.ui.design.HarnessTheme
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class)
class ArtifactSelectionTest {
    @Test fun selectedMarkdownCanBeCopiedFromTheContextMenu() = javax.swing.SwingUtilities.invokeAndWait {
        var copied: String? = null
        val clipboard = object : Clipboard {
            override suspend fun getClipEntry(): ClipEntry? = null
            override suspend fun setClipEntry(clipEntry: ClipEntry?) {
                copied = clipEntry?.asAwtTransferable?.getTransferData(DataFlavor.stringFlavor) as? String
            }
        }
        val text = "Copy this passage."
        val info = ArtifactInfo("a1", "Document", "md", "main", null, 0, 0)
        val state = ArtifactsState(Route.Artifacts(info.id), open = ArtifactData(info, "**$text**"))
        ImageComposeScene(600, 500, Density(1f), content = {
            CompositionLocalProvider(LocalClipboard provides clipboard) {
                HarnessTheme(dark = false) { ArtifactsContent(state, emptyList(), 0, ArtifactsActions()) }
            }
        }).use { scene ->
            var time = 0L
            fun frame() { repeat(5) { scene.render(time.also { time += 200_000_000 }) } }
            fun nodes(): List<SemanticsNode> {
                fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
                return scene.semanticsOwners.flatMap { flatten(it.unmergedRootSemanticsNode) }
            }
            frame()
            assertFalse(nodes().any { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Copy") == true })
            val prose = nodes().first { it.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true }
            val layouts = mutableListOf<TextLayoutResult>()
            prose.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
            val first = layouts.single().getBoundingBox(0)
            val last = layouts.single().getBoundingBox(text.lastIndex)
            val start = prose.boundsInRoot.topLeft + Offset(first.left, first.center.y)
            val end = prose.boundsInRoot.topLeft + Offset(last.right, last.center.y)
            scene.sendPointerEvent(PointerEventType.Press, start, timeMillis = 1100)
            scene.sendPointerEvent(PointerEventType.Move, end, timeMillis = 1400)
            scene.sendPointerEvent(PointerEventType.Release, end, timeMillis = 1500)
            frame()
            scene.sendPointerEvent(PointerEventType.Press, start + Offset(5f, 0f), timeMillis = 2100,
                buttons = PointerButtons(isSecondaryPressed = true), button = PointerButton.Secondary)
            scene.sendPointerEvent(PointerEventType.Release, start + Offset(5f, 0f), timeMillis = 2200,
                buttons = PointerButtons(), button = PointerButton.Secondary)
            frame()
            var copy = nodes().first { it.config.getOrNull(SemanticsProperties.Text)?.any { it.text == "Copy" } == true }
            while (copy.config.getOrNull(SemanticsActions.OnClick) == null) copy = requireNotNull(copy.parent)
            copy.config[SemanticsActions.OnClick].action!!.invoke()
            frame()
            assertEquals(text, copied)
        }
    }
}
