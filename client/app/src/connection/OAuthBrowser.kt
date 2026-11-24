package kinuvo.app.connection

import kinuvo.client.oauthState
import kinuvo.protocol.mcp.McpRedirect
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first

/** Opens an OAuth sign-in page and waits for the browser to come back to [redirect]. */
interface OAuthBrowser {
    val redirect: McpRedirect

    suspend fun signInWithLoopback(authorize: suspend (String) -> String, opened: (String, Boolean) -> Unit): String? {
        val url = authorize("http://127.0.0.1:1455/auth/callback")
        opened(url, true)
        return openLoopbackPage(url)
    }

    suspend fun openLoopbackPage(url: String): String? = null


    /** The URL the browser came back to; null when the user closed the page. */
    suspend fun signIn(url: String): String?
}

/** [McpRedirect.App] callbacks the app was opened with; see [openLink]. */
val oauthCallbacks = MutableSharedFlow<String>(extraBufferCapacity = 1)

/** Opens the system browser with [open] and waits for the app to be opened with the [McpRedirect.App] callback. */
class AppSchemeBrowser(private val open: (String) -> Unit) : OAuthBrowser {
    override val redirect = McpRedirect.App
    override suspend fun openLoopbackPage(url: String): String? { open(url); return null }

    override suspend fun signIn(url: String): String? = coroutineScope {
        val state = oauthState(url)
        val callback = async(start = CoroutineStart.UNDISPATCHED) { oauthCallbacks.first { oauthState(it) == state } }
        open(url)
        callback.await()
    }
}

/** The page stays available while awaiting a loopback callback or a pasted URL. */
data class ProviderSignInState(val provider: String, val url: String? = null, val awaitingPaste: Boolean = false, val error: String? = null)
