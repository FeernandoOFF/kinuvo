package kinuvo.ui.design

import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.*
import com.composables.ui.components.*

@Composable
fun Modal(modifier: Modifier = Modifier, trigger: @Composable () -> Unit, content: @Composable () -> Unit) {
    var open by remember { mutableStateOf(false) }
    val context = currentCompositionLocalContext
    Box(modifier.semantics(mergeDescendants = true) { onClick("Open") { open = true; true } }.onPreviewKeyEvent { event ->
        if (event.key == Key.Enter || event.key == Key.Spacebar) {
            if (event.type == KeyEventType.KeyUp) open = true
            true
        } else false
    }.focusable().pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val up = waitForUpOrCancellation(PointerEventPass.Initial) ?: return@awaitEachGesture
            up.consume()
            open = true
        }
    }) { trigger() }
    Popover(open, { open = false }) {
        CompositionLocalProvider(context) {
            content()
            Button(onClick = { open = false }, style = ButtonStyle.Ghost) { Text("Close") }
        }
    }
}
