package kinuvo.ui.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import app.rive.runtime.kotlin.core.Rive

@Composable
internal actual fun initializeRive(): Boolean {
    val context = LocalContext.current.applicationContext
    remember(context) { Rive.init(context) }
    return true
}
