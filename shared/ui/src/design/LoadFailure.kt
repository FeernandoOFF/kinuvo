package kinuvo.ui.design

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.*
import com.composables.ui.components.*
import com.composables.ui.theme.*

@Composable
fun ManagementAccessRequired() = ContentUnavailable(
    Lucide.LockKeyhole,
    "Management access required",
    "You need management access to change settings on this Server.",
    "On a Device with management access, open Settings → Connection → Pair a management device. Use that code to pair this Device again.",
)

@Composable
fun LoadFailure(message: String, action: String = "Retry", onAction: () -> Unit) {
    if (message == "This action requires management access") ManagementAccessRequired()
    else ContentUnavailable(Lucide.CloudOff, "Couldn't load this page", message, action = action, onAction = onAction)
}

/** Shared full-page guidance for unavailable content, permissions and failed requests. */
@Composable
private fun ContentUnavailable(
    icon: ImageVector,
    title: String,
    description: String,
    instructions: String? = null,
    action: String? = null,
    onAction: () -> Unit = {},
) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 40.dp), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 400.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(icon, Modifier.size(48.dp), tint = color(mutedColor))
            Text(title, fontSize = 20.sp, lineHeight = 26.sp, textAlign = TextAlign.Center)
            Text(description, fontSize = 14.sp, lineHeight = 21.sp, textAlign = TextAlign.Center)
            instructions?.let { Text(it, color = color(mutedColor), fontSize = 12.sp, lineHeight = 18.sp, textAlign = TextAlign.Center) }
            action?.let { Button(onClick = onAction, style = ButtonStyle.Outlined) { Text(it) } }
        }
    }
}
