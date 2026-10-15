package kinuvo.web

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import kinuvo.ui.KinuvoApp
import kinuvo.ui.startKinuvo
import kinuvo.ui.openBrowserPairingLink

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    startKinuvo()
    openBrowserPairingLink()
    ComposeViewport { KinuvoApp() }
}
