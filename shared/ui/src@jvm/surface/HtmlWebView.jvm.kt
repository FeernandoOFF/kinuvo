package kinuvo.ui.surface

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.composables.ui.components.Text
import java.util.Locale

@Composable
internal actual fun HtmlWebView(document: String, bridge: HtmlBridge, modifier: Modifier) {
    Text("HTML Surface — desktop host pending", modifier)
}

internal actual val htmlSurfacePlatform = "desktop"
internal actual fun htmlSurfaceLocale(): String = Locale.getDefault().toLanguageTag()
