package kinuvo.ui

import kinuvo.app.connection.*

import kinuvo.protocol.McpRedirect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
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

/** Signs in through an in-app `ASWebAuthenticationSession`, which catches the [McpRedirect.App] callback itself. */
internal class WebAuthBrowser : OAuthBrowser {
    override val redirect = McpRedirect.App
    private var session: ASWebAuthenticationSession? = null

    private val anchor = object : NSObject(), ASWebAuthenticationPresentationContextProvidingProtocol {
        @Suppress("DEPRECATION")
        override fun presentationAnchorForWebAuthenticationSession(session: ASWebAuthenticationSession): ASPresentationAnchor =
            UIApplication.sharedApplication.keyWindow ?: UIWindow()
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
