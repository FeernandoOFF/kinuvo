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
