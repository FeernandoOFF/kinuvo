package kinuvo.ui.agent

import kinuvo.app.connection.*
import kinuvo.app.navigation.*
import kinuvo.ui.session.*
import kinuvo.ui.routine.*
import kinuvo.ui.navigation.*

import androidx.compose.foundation.background
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.*
import com.composables.ui.components.*
import com.composables.ui.theme.*
import kinuvo.ui.design.*
import com.composeunstyled.LocalTextStyle
import kotlinx.coroutines.delay
import kotlin.time.Clock

/** An Agent's Overview in Settings: its greeting and avatar among cards opening its other sections: Personality and Routines, then what it can use. */
@Composable
fun AgentContent(ui: SettingsUi, onOpen: (Route.Settings) -> Unit, onBack: (() -> Unit)? = null) {
    val agentId = ui.route.agentId
    val open = { section: SettingsSection -> onOpen(Route.Settings(agentId, section)) }
    BoxWithConstraints(Modifier.fillMaxSize().background(color(backgroundColor))) {
        val wide = maxWidth >= 900.dp
        Column(Modifier.fillMaxSize()) {
            TitleBarRow(leading = onBack != null, trailing = false) {
                onBack?.let { HeaderButton(Lucide.ArrowLeft, "Back", it) }
            }
            val personality: @Composable (Modifier) -> Unit = { PersonalityCard(ui, it) { open(SettingsSection.Agent) } }
            val routines: @Composable (Modifier) -> Unit = { RoutinesCard(ui, it, { open(SettingsSection.Routines) }) { onOpen(Route.Settings(agentId, SettingsSection.Routines, editing = true)) } }
            val tiles: @Composable (Modifier) -> Unit = { m -> Tiles(ui, wide, m, open) }
            val greeting = "Hi, I’m ${ui.agent?.name ?: "…"}"
            if (wide) Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp).clip(RoundedCornerShape(28.dp)).background(color(sidebarColor))) {
                // Between the cards, shrinking and wrapping for long names.
                Greeting(greeting, Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(top = 96.dp, start = 360.dp, end = 360.dp).height(144.dp), 56.sp)
                AgentAvatar(ui.agent, 440.dp, Modifier.align(Alignment.Center))
                personality(Modifier.align(Alignment.TopStart).padding(20.dp).width(320.dp).height(280.dp))
                routines(Modifier.align(Alignment.TopEnd).padding(20.dp).width(320.dp).height(280.dp))
                tiles(Modifier.align(Alignment.BottomCenter).padding(20.dp))
            } else Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Greeting(greeting, Modifier.padding(top = 8.dp).fillMaxWidth().heightIn(max = 96.dp), 36.sp)
                AgentAvatar(ui.agent, 200.dp)
                personality(Modifier.fillMaxWidth())
                routines(Modifier.fillMaxWidth())
                tiles(Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun Greeting(text: String, modifier: Modifier, max: TextUnit) = BasicText(
    text,
    modifier,
    style = LocalTextStyle.current.copy(color = color(onBackgroundColor), textAlign = TextAlign.Center, lineHeight = 1.15.em),
    maxLines = 2,
    overflow = TextOverflow.Ellipsis,
    autoSize = TextAutoSize.StepBased(minFontSize = 24.sp, maxFontSize = max),
)

private val CardShape = RoundedCornerShape(24.dp)

@Composable
private fun Card(icon: ImageVector, title: String, modifier: Modifier, onClick: () -> Unit, enabled: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.clip(CardShape).background(color(accentColor).copy(alpha = 0.16f)).clickable(enabled = enabled, onClick = onClick).alpha(if (enabled) 1f else 0.5f).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(icon, Modifier.size(20.dp))
            Text(title, fontWeight = FontWeight.Bold)
        }
        content()
    }
}

/** Who the Agent is, from its Identity and Soul. */
@Composable
private fun PersonalityCard(ui: SettingsUi, modifier: Modifier, onClick: () -> Unit) {
    val details = ui.config.details
    Card(Lucide.Sparkles, "Personality", modifier, onClick) {
        if (details == null) Spinner()
        else {
            prose(details.identity).takeIf { it.isNotBlank() }?.let { Text(it, maxLines = 4, overflow = TextOverflow.Ellipsis) }
            details.soul.lines().map { it.trim() }.filter { it.startsWith("- ") }.take(3).forEach {
                Text("· " + it.removePrefix("- "), fontSize = 13.sp, lineHeight = 18.sp, color = color(mutedColor), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Markdown [text] without its headings, as one paragraph. */
private fun prose(text: String) = text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.joinToString(" ")

@Composable
private fun RoutinesCard(ui: SettingsUi, modifier: Modifier, onClick: () -> Unit, onNew: () -> Unit) {
    val routines = ui.state.routines
    val enabled = ui.available(SettingsSection.Routines)
    Card(Lucide.CalendarClock, "Routines", modifier, onClick, enabled) {
        when {
            !enabled -> Text(UNAVAILABLE, color = color(mutedColor))
            routines == null -> Spinner()
            routines.isEmpty() -> {
                Spacer(Modifier.weight(1f, fill = false))
                Text("Nothing scheduled yet", style = LocalTextStyle.current.copy(fontStyle = FontStyle.Italic, fontWeight = FontWeight.Bold))
                Row(Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onNew), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Set one up")
                    Icon(Lucide.ArrowRight, Modifier.size(14.dp))
                }
            }
            else -> routines.take(3).forEach { r ->
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(color(backgroundColor).copy(alpha = 0.4f)).padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Text(r.name, singleLine = true, overflow = TextOverflow.Ellipsis)
                    Text(r.summary(ui.now), fontSize = 13.sp, color = color(mutedColor), singleLine = true, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** What the Agent can use and run on, each opening its section. Phones stack them. */
@Composable
private fun Tiles(ui: SettingsUi, wide: Boolean, modifier: Modifier, open: (SettingsSection) -> Unit) {
    val settings = ui.config.details?.settings
    val tiles = listOf(
        Triple(Lucide.Zap, "Skills", ui.config.skills?.count { it.active }?.let { "$it skills" }) to SettingsSection.Skills,
        Triple(Lucide.Wrench, "Tools", settings?.let { s -> "${s.tools.size} built-in" + ui.config.mcp?.count { it.active }?.let { " · $it MCP" }.orEmpty() }) to SettingsSection.Tools,
        Triple(Lucide.FolderOpen, "Workspace", settings?.let { s -> listOfNotNull(EXEC[s.exec] ?: s.exec, s.workspace.firstOrNull()).joinToString(" · ") }) to SettingsSection.Workspace,
    )
    val tile: @Composable (Modifier, Int) -> Unit = { m, i ->
        val (info, section) = tiles[i]
        val enabled = ui.available(section)
        Tile(info.first, info.second, if (enabled) info.third else UNAVAILABLE, m, enabled) { open(section) }
    }
    if (wide) Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) { tiles.indices.forEach { tile(Modifier.weight(1f), it) } }
    else Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) { tiles.indices.forEach { tile(Modifier.fillMaxWidth(), it) } }
}

@Composable
private fun Tile(icon: ImageVector, title: String, subtitle: String?, modifier: Modifier, enabled: Boolean, onClick: () -> Unit) {
    Row(
        modifier.clip(RoundedCornerShape(16.dp)).background(color(railColor)).clickable(enabled = enabled, onClick = onClick).alpha(if (enabled) 1f else 0.5f).padding(horizontal = 24.dp, vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(icon, Modifier.size(22.dp), tint = color(mutedColor))
        Column {
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.Bold, singleLine = true)
            Text(subtitle ?: "…", fontSize = 13.sp, color = color(mutedColor), singleLine = true, overflow = TextOverflow.Ellipsis)
        }
    }
}

internal const val UNAVAILABLE = "Not available on this Server"
