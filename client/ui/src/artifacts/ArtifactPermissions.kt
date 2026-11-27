package kinuvo.ui.artifacts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.composables.icons.lucide.*
import com.composables.ui.components.*
import com.composables.ui.theme.*
import kinuvo.protocol.artifact.ArtifactCapabilities
import kinuvo.protocol.artifact.ArtifactPermissions
import kinuvo.ui.design.*
import kinuvo.ui.settings.relative

/** What the sheet changes: everything at once, or one capability. */
internal class PermissionActions(
    val onAll: (Boolean) -> Unit = {},
    val onGrant: (ArtifactCapabilities) -> Unit = {},
    val onRevoke: (ArtifactCapabilities) -> Unit = {},
)

/**
 * What one Artifact folder asks for and what the owner allows, a switch each; nothing is allowed until switched on, here or by [PermissionActions.onAll],
 * which allows exactly what is asked for now. Only management Devices ([canGrant]) can change anything, as the Server refuses others.
 */
@Composable
internal fun ArtifactPermissionsPanel(permissions: ArtifactPermissions, canGrant: Boolean, now: Long, actions: PermissionActions, modifier: Modifier = Modifier) {
    val requested = permissions.requested
    val allowed = permissions.effective
    fun toggle(on: Boolean, one: ArtifactCapabilities) = if (on) actions.onGrant(one) else actions.onRevoke(one)
    Column(modifier.fillMaxWidth().padding(Spacing.space4), verticalArrangement = Arrangement.spacedBy(Spacing.space12)) {
        Text("This artifact asks for more than its own files. Nothing is allowed until you say so; your choice applies on every device.", style = TextStyles.label, color = color(mutedColor))
        if (!canGrant) Pill("Only a management device can change these.", Modifier.fillMaxWidth(), tone = Tone.Warning, icon = Lucide.ShieldAlert)
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.space6)) {
            RowGroup {
                SwitchRow("Allow everything it asks for", permissions.everything, actions.onAll, icon = Lucide.ShieldAlert, enabled = canGrant)
            }
            Text(
                "Artifacts are made by your Agent. Network access lets the page send its own content to those sites.",
                Modifier.padding(horizontal = Spacing.space6), style = TextStyles.secondary, color = color(mutedColor),
            )
        }
        if (requested.network.isNotEmpty()) RowGroup("Network") {
            requested.network.forEach { origin ->
                SwitchRow(origin, origin in allowed.network, { toggle(it, ArtifactCapabilities(network = listOf(origin))) }, networkNote(origin), Lucide.Globe, canGrant)
            }
        }
        if (requested.popups) RowGroup("Windows") {
            SwitchRow(
                "Pop-up windows", allowed.popups, { toggle(it, ArtifactCapabilities(popups = true)) },
                if (popupsSupported) "Lets the page open windows, like a presenter view. Other sites open in your browser."
                else "Lets the page open windows, like a presenter view. Not on this device yet.",
                Lucide.AppWindow, canGrant,
            )
        }
        if (requested.storage || requested.tools.isNotEmpty()) RowGroup("Coming soon") {
            if (requested.storage) SwitchRow("Keep data on this device", allowed.storage, { toggle(it, ArtifactCapabilities(storage = true)) }, "Not used yet; your choice is kept.", Lucide.Database, canGrant)
            requested.tools.forEach { tool ->
                SwitchRow("Use the $tool tool", tool in allowed.tools, { toggle(it, ArtifactCapabilities(tools = listOf(tool))) }, "Not used yet; your choice is kept.", Lucide.Wrench, canGrant)
            }
        }
        permissions.at?.let { at ->
            Text(
                "Last changed ${permissions.byName?.let { "on $it " }.orEmpty()}${relative(at, now)}.",
                style = TextStyles.caption, color = color(mutedColor), fontWeight = FontWeight.Normal,
            )
        }
    }
}

/** One line on what reaching [origin] lets the page do. */
internal fun networkNote(origin: String): String {
    val subdomains = if ("://*." in origin) ", any subdomain" else ""
    return if (origin.startsWith("wss://")) "Keep a live connection open (WebSocket)$subdomains." else "Send and receive data$subdomains."
}

/** The sheet's title, which says when something asked for is still waiting for an answer. */
internal fun permissionsTitle(permissions: ArtifactPermissions) = if (permissions.pending) "Permissions requested" else "Permissions"
