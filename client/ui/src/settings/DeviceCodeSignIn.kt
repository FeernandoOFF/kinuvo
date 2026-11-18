package kinuvo.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.*
import com.composables.ui.components.*
import com.composables.ui.theme.*
import com.composeunstyled.theme.Theme
import kinuvo.protocol.model.DeviceCode
import kinuvo.ui.design.*
import kotlinx.coroutines.delay

/** A browser sign-in code: one button copies it and opens the page, so neither step is missed. */
@Suppress("DEPRECATION")
@Composable
internal fun DeviceCodeSignIn(code: DeviceCode, expiry: String? = null) {
    val clipboard = LocalClipboardManager.current
    val uri = LocalUriHandler.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(2000); copied = false } }
    val copy = { clipboard.setText(AnnotatedString(code.code)); copied = true }
    GroupBox {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.space2)) {
            Text("Finish signing in", style = TextStyles.label.copy(fontWeight = FontWeight.SemiBold))
            Text("Copy this code, then paste it on the sign-in page.", style = TextStyles.secondary, color = color(mutedColor))
        }
        Row(Modifier.fillMaxWidth().background(color(subtleColor), Theme[shapes][mediumShape]).padding(start = Spacing.space16, end = Spacing.space6, top = Spacing.space6, bottom = Spacing.space6),
            verticalAlignment = Alignment.CenterVertically) {
            Text(code.code, Modifier.weight(1f), style = MonoStyle, fontSize = TextStyles.headline.fontSize, fontWeight = FontWeight.Bold, singleLine = true)
            Button(onClick = copy, style = ButtonStyle.Ghost, buttonSize = ButtonSize.Small) {
                Icon(if (copied) Lucide.Check else Lucide.Copy, Modifier.size(15.dp))
                Spacer(Modifier.width(Spacing.space6))
                Text(if (copied) "Copied" else "Copy", singleLine = true)
            }
        }
        Button(onClick = { copy(); runCatching { uri.openUri(code.url) } }, modifier = Modifier.fillMaxWidth(), style = ButtonStyle.Primary) {
            Icon(Lucide.ExternalLink, Modifier.size(15.dp))
            Spacer(Modifier.width(Spacing.space6))
            Text("Copy code and open sign-in page", singleLine = true)
        }
        expiry?.let { Text(it, style = TextStyles.caption, color = color(mutedColor)) }
    }
}
