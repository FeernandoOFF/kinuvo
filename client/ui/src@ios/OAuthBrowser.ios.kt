package kinuvo.ui

import kinuvo.app.connection.*
import kinuvo.client.oauthState
import kinuvo.ui.connection.LoopbackListener
import kinuvo.ui.connection.awaitLoopbackCallback
import kinuvo.ui.connection.bindLoopbackListener
import kinuvo.protocol.mcp.McpRedirect
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
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
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import kotlin.coroutines.resume

/**
 * Signs in through an in-app `ASWebAuthenticationSession`, which catches the [McpRedirect.App] callback itself.
 * Provider sign-ins (Sign in with ChatGPT) instead catch their `127.0.0.1` redirect on an in-app loopback listener.
 */
internal class WebAuthBrowser : OAuthBrowser {
    override val redirect = McpRedirect.App
    private var session: ASWebAuthenticationSession? = null
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

    override suspend fun signIn(url: String): String? = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            val s = ASWebAuthenticationSession(NSURL(string = url), McpRedirect.App.uri.substringBefore(':')) { callback, _ ->
                session = null
                if (cont.isActive) cont.resume(callback?.absoluteString)
            }
            s.presentationContextProvider = anchor
            session = s
            s.start()
            cont.invokeOnCancellation { dispatch_async(dispatch_get_main_queue()) { s.cancel() } }
        }
    }
}
