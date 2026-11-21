package kinuvo.ui.session

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import kinuvo.protocol.session.UploadAttachmentParams

/** Files dropped anywhere in the window: the chat on screen takes them, or a new chat does when none is shown. */
internal class DroppedFiles {
    class Target(val accepts: Boolean, val add: (List<UploadAttachmentParams>, String?) -> Unit)

    val targets = mutableStateListOf<Target>()
    var pending by mutableStateOf<Pair<List<UploadAttachmentParams>, String?>?>(null)
}

internal val LocalDroppedFiles = staticCompositionLocalOf<DroppedFiles?> { null }

/** Registers a chat for window-wide drops while it's shown; a new chat also takes files dropped before it opened. */
@Composable
internal fun ChatDropTarget(drops: DroppedFiles, accepts: Boolean, compose: Boolean, add: (List<UploadAttachmentParams>, String?) -> Unit) {
    val latest by rememberUpdatedState(add)
    val target = remember(accepts) { DroppedFiles.Target(accepts) { files, error -> latest(files, error) } }
    DisposableEffect(drops, target) {
        drops.targets.add(target)
        onDispose { drops.targets.remove(target) }
    }
    if (compose && accepts) LaunchedEffect(drops.pending) {
        val (files, error) = drops.pending ?: return@LaunchedEffect
        drops.pending = null
        latest(files, error)
    }
}

@Composable
internal fun AppDrop(onNewChat: () -> Unit, content: @Composable () -> Unit) {
    val drops = remember { DroppedFiles() }
    var hovering by remember { mutableStateOf(false) }
    val target = drops.targets.lastOrNull()
    val accepts = target?.accepts ?: true
    val newChat by rememberUpdatedState(onNewChat)
    Box(Modifier.fillMaxSize().attachmentDrop(accepts, { files, error ->
        val shown = drops.targets.lastOrNull()
        if (shown != null) shown.add(files, error) else { drops.pending = files to error; newChat() }
    }) { hovering = it }) {
        CompositionLocalProvider(LocalDroppedFiles provides drops) { content() }
        AnimatedVisibility(hovering && accepts, Modifier.matchParentSize(), enter = fadeIn(), exit = fadeOut()) {
            DropOverlay(if (target == null) "Drop to start a chat" else "Drop to attach")
        }
    }
}
