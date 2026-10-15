package kinuvo.web

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import kinuvo.ui.KinuvoApp
import kinuvo.ui.startKinuvo
import kinuvo.ui.openBrowserPairingLink
import kotlinx.browser.document
import org.w3c.dom.HTMLLinkElement

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    document.title = "Kinuvo"
    document.head?.appendChild((document.createElement("link") as HTMLLinkElement).apply {
        rel = "icon"
        href = "composeResources/ui.generated.resources/files/icon.png"
    })
    startKinuvo()
    openBrowserPairingLink()
    ComposeViewport { KinuvoApp() }
}
