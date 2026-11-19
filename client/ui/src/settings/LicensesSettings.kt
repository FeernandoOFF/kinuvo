package kinuvo.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalUriHandler
import com.composables.icons.lucide.*
import com.composables.ui.components.*
import com.composables.ui.theme.*
import kinuvo.app.navigation.Route
import kinuvo.app.settings.OpenSourceLicense
import kinuvo.app.settings.openSourceLicenses
import kinuvo.ui.design.*
import ui.generated.resources.Res

@Composable
internal fun LicensesSettingsPane(route: Route.Settings, go: (Route.Settings) -> Unit, rootBack: (() -> Unit)?, up: () -> Unit) {
    val open = openSourceLicenses.firstOrNull { it.name == route.item }
    if (open != null) return LicenseDetail(open, up)
    DetailPane(rootBack, "Open source licenses", subtitle = "Kinuvo includes these projects") {
        RowGroup {
            openSourceLicenses.forEach { GroupRow(it.name, { go(route.copy(item = it.name)) }, subtitle = listOfNotNull(it.license, it.copyright).joinToString(" · "), titleLines = 2) }
        }
    }
}

@Composable
private fun LicenseDetail(entry: OpenSourceLicense, back: () -> Unit) {
    val uri = LocalUriHandler.current
    val texts by produceState(emptyList<String>(), entry) {
        value = entry.texts.mapNotNull { runCatching { Res.readBytes("files/licenses/$it.txt").decodeToString() }.getOrNull() }
    }
    DetailPane(back, entry.name, subtitle = entry.license) {
        entry.copyright?.let { Text("Copyright $it") }
        RowGroup {
            ActionRow("Project page", Lucide.ExternalLink, { runCatching { uri.openUri(entry.url) } }, subtitle = entry.url)
            entry.source?.let { ActionRow("Source code", Lucide.Code, { runCatching { uri.openUri(it) } }, subtitle = it) }
        }
        texts.forEach { SelectionContainer { Text(it, style = MonoStyle, color = color(mutedColor)) } }
    }
}
