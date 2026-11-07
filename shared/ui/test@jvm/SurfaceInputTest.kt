package kinuvo.ui

import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.text.TextRange
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import kinuvo.ui.design.accentColor
import kinuvo.ui.design.HarnessTheme
import kinuvo.ui.design.Input
import kinuvo.ui.design.color
import org.jetbrains.skia.EncodedImageFormat
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.test.*

class SurfaceInputTest {
    @Test fun pointerRepositionsCaretAndDragsSelection() = javax.swing.SwingUtilities.invokeAndWait {
        val state = TextFieldState("Trip name budget", TextRange(16))
        ImageComposeScene(300, 100, Density(1f), content = {
            HarnessTheme(dark = false) {
                SelectionContainer { Box(Modifier.padding(10.dp)) { Input(state, "") } }
            }
        }).use { scene ->
            var frame = 0L
            fun settle() { repeat(5) { scene.render(frame.also { frame += 100_000_000L }) } }
            fun event(type: PointerEventType, x: Float, time: Long) =
                scene.sendPointerEvent(type, Offset(x, 30f), timeMillis = time)
            settle()
            event(PointerEventType.Press, 24f, 1000)
            event(PointerEventType.Release, 24f, 1050)
            settle()
            assertEquals(TextRange(0), state.selection)
            event(PointerEventType.Press, 24f, 2000)
            event(PointerEventType.Move, 95f, 2400)
            event(PointerEventType.Release, 95f, 2450)
            settle()
            assertFalse(state.selection.collapsed, "Dragging should select input text inside the chat SelectionContainer")
        }
    }

    @Test fun focusedInputDrawsCaret() = javax.swing.SwingUtilities.invokeAndWait {
        for (limits in listOf(TextFieldLineLimits.SingleLine, TextFieldLineLimits.MultiLine(maxHeightInLines = 4))) {
            var accent = 0
            ImageComposeScene(300, 100, Density(1f), content = {
                HarnessTheme(dark = false) {
                    accent = color(accentColor).toArgb()
                    Box(Modifier.padding(10.dp)) { Input(rememberTextFieldState(), "", lineLimits = limits) }
                }
            }).use { scene ->
                var frame = 0L
                fun render() = scene.render(frame.also { frame += 100_000_000L })
                repeat(5) { render() }
                fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
                val field = scene.semanticsOwners.flatMap { flatten(it.unmergedRootSemanticsNode) }
                    .first { it.config.getOrNull(SemanticsActions.SetText) != null }
                assertTrue(field.config[SemanticsActions.RequestFocus].action!!.invoke())
                var caretPixels = 0
                repeat(10) {
                    val image = ImageIO.read(ByteArrayInputStream(render().encodeToData(EncodedImageFormat.PNG)!!.bytes))
                    // Exclude the border: an empty field has only its caret inside.
                    caretPixels = maxOf(caretPixels, (15 until 45).sumOf { y ->
                        (15 until 50).count { x -> image.getRGB(x, y) == accent }
                    })
                }
                assertTrue(caretPixels >= 10, "Missing caret for $limits ($caretPixels accent pixels)")
            }
        }
    }
}
