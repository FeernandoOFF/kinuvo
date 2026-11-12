package kinuvo.app.screen

import kinuvo.protocol.ScreenAction
import kinuvo.protocol.ScreenActionKind

const val RemoteInputAnchor = "\u200b"

data class RemoteTextEdit(val text: String, val actions: List<ScreenAction> = emptyList())

fun remoteTextEdit(text: String, composing: Boolean): RemoteTextEdit = when {
    composing -> RemoteTextEdit(text)
    text.isEmpty() -> RemoteTextEdit(RemoteInputAnchor, listOf(ScreenAction(ScreenActionKind.Key, key = "Backspace")))
    else -> RemoteTextEdit(RemoteInputAnchor, text.removePrefix(RemoteInputAnchor).takeIf { it.isNotEmpty() }
        ?.let { listOf(ScreenAction(ScreenActionKind.Type, text = it)) }.orEmpty())
}
