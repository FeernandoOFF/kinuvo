package kinuvo.ui.artifacts

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.composables.ui.components.Button
import com.composables.ui.components.Text
import kotlinx.browser.window

/** Compose draws the page on a canvas, so the page opens in its own tab instead. */
@Composable
internal actual fun WebView(html: String, modifier: Modifier) = Box(modifier, contentAlignment = Alignment.Center) {
    Button(onClick = { window.open("", "_blank")?.let { tab -> tab.opener = null; tab.document.run { open(); write(html); close() } } }) { Text("Open in a new tab") }
}
