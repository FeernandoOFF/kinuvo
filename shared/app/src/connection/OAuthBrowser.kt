package kinuvo.app.connection

import kinuvo.client.oauthState
import kinuvo.protocol.McpRedirect
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first

/** Opens an OAuth sign-in page and waits for the browser to come back to [redirect]. */
interface OAuthBrowser {
    val redirect: McpRedirect

    /** The URL the browser came back to; null when the user closed the page. */
    suspend fun signIn(url: String): String?
}

/** [McpRedirect.App] callbacks the app was opened with; see [openLink]. */
val oauthCallbacks = MutableSharedFlow<String>(extraBufferCapacity = 1)

/** Opens the system browser with [open] and waits for the app to be opened with the [McpRedirect.App] callback. */
class AppSchemeBrowser(private val open: (String) -> Unit) : OAuthBrowser {
    override val redirect = McpRedirect.App

    override suspend fun signIn(url: String): String? = coroutineScope {
        val state = oauthState(url)
        val callback = async(start = CoroutineStart.UNDISPATCHED) { oauthCallbacks.first { oauthState(it) == state } }
        open(url)
        callback.await()
    }
}
