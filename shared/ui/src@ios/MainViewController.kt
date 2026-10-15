package kinuvo.ui

import androidx.compose.ui.window.ComposeUIViewController
import org.koin.mp.KoinPlatform
import platform.UIKit.UIViewController

fun mainViewController(): UIViewController {
    if (KoinPlatform.getKoinOrNull() == null) startKinuvo()
    return ComposeUIViewController { KinuvoApp() }
}
