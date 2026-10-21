package kinuvo.app.connection

import kinuvo.app.navigation.*

import kinuvo.protocol.Links
import kinuvo.protocol.McpRedirect
import kinuvo.protocol.PairLink
import kotlinx.coroutines.flow.MutableStateFlow

/** The last `kinuvo://` link the platform opened the app with, until the app shows it. */
val pendingLink = MutableStateFlow<Route.Chat?>(null)
val pendingPairLink = MutableStateFlow<PairLink?>(null)

/** Called by the platform shells when the app is opened with [url]; ignores links it doesn't know. */
fun openLink(url: String) {
    if (url.startsWith(McpRedirect.App.uri)) {
        oauthCallbacks.tryEmit(url)
        return
    }
    Links.parsePair(url)?.let { pendingPairLink.value = it; return }
    Links.parseChat(url)?.let { (agent, session) -> pendingLink.value = Route.Chat(agent, session) }
}

/** A new Session a platform shortcut asked for, until the SessionViewModel for [slot] takes it: [voice] starts voice chat, [message] is sent. */
data class NewChatRequest(val slot: String, val voice: Boolean = false, val message: String? = null)

val pendingNewChat = MutableStateFlow<NewChatRequest?>(null)

/**
 * Opens a new Session with the shown Agent, e.g. from an App Shortcut. Not a `kinuvo://` link, so other apps and
 * websites can't send messages.
 */
fun openNewChat(voice: Boolean, message: String?) {
    val route = Route.Chat(compose = !voice && message.isNullOrBlank())
    pendingNewChat.value = NewChatRequest(route.slot, voice, message?.ifBlank { null })
    pendingLink.value = route
}
