package kinuvo.ui

import kinuvo.app.connection.*
import kinuvo.client.oauthState
import kinuvo.ui.connection.LoopbackListener
import kinuvo.ui.connection.awaitLoopbackCallback
import kinuvo.ui.connection.bindFixedLoopbackListener
import kinuvo.ui.connection.bindLoopbackListener
import kinuvo.protocol.mcp.McpRedirect
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.minutes
import platform.AuthenticationServices.ASPresentationAnchor
import platform.AuthenticationServices.ASWebAuthenticationPresentationContextProvidingProtocol
import platform.AuthenticationServices.ASWebAuthenticationSession
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIWindow
import platform.darwin.NSObject

/**
 * Signs in through an in-app `ASWebAuthenticationSession`, catching the real redirect on an in-app loopback listener
 * ([McpRedirect.Loopback]) rather than the session's own custom-scheme interception: some MCP auth servers mishandle a
 * non-http redirect_uri (observed concatenating it onto their own base URL instead of navigating to it), stranding the
 * sign-in with no way back to the app and no address bar to copy the stuck page from. The loopback redirect avoids that
 * failure mode entirely, so it's used for every MCP sign-in on iOS, not just as a fallback.
 */
internal class WebAuthBrowser : OAuthBrowser {
    override val redirect = McpRedirect.Loopback
    override suspend fun openLoopbackPage(url: String): String? = withContext(Dispatchers.Main) {
        UIApplication.sharedApplication.openURL(NSURL(string = url)!!)
        null
    }

    private val anchor = object : NSObject(), ASWebAuthenticationPresentationContextProvidingProtocol {
        @Suppress("DEPRECATION")
        override fun presentationAnchorForWebAuthenticationSession(session: ASWebAuthenticationSession): ASPresentationAnchor =
            UIApplication.sharedApplication.keyWindow ?: UIWindow()
    }

    /** Presents [url] in-app and catches the real redirect on [listener]; the callback scheme never fires. */
    private suspend fun raceLoopback(url: String, listener: LoopbackListener): String? {
        val cancelledByUser = CompletableDeferred<Unit>()
        var session: ASWebAuthenticationSession? = null
        return try {
            val s = ASWebAuthenticationSession(NSURL(string = url), McpRedirect.App.uri.substringBefore(':')) { _, _ ->
                cancelledByUser.complete(Unit)
            }
            s.presentationContextProvider = anchor
            s.prefersEphemeralWebBrowserSession = false
            session = s
            if (!s.start()) cancelledByUser.complete(Unit)
            withContext(Dispatchers.Default) {
                awaitLoopbackCallback(listener, oauthState(url)) { cancelledByUser.isCompleted }
            }
        } finally {
            withContext(Dispatchers.Main + NonCancellable) { session?.cancel() }
        }
    }

    override suspend fun signInWithLoopback(authorize: suspend (String) -> String, opened: (String, Boolean) -> Unit): String? =
        withContext(Dispatchers.Main) {
            withTimeoutOrNull(10.minutes) {
                val listener = bindLoopbackListener(1455)
                try {
                    val redirect = "http://127.0.0.1:${listener.port}/auth/callback"
                    val url = authorize(redirect)
                    opened(url, false)
                    raceLoopback(url, listener)
                } finally { listener.close() }
            }
        }

    /** Catches the callback on [McpRedirect.Loopback]'s fixed port; null (not an error) when it's taken, so the caller falls back to pasting. */
    override suspend fun signIn(url: String): String? = withContext(Dispatchers.Main) {
        val listener = bindFixedLoopbackListener(McpRedirect.LOOPBACK_PORT) ?: return@withContext null
        try { withTimeoutOrNull(10.minutes) { raceLoopback(url, listener) } } finally { listener.close() }
    }
}
