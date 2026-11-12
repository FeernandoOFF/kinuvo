package kinuvo.ui.session

import kinuvo.app.connection.*
import kinuvo.ui.settings.*
import kinuvo.ui.board.*
import kinuvo.ui.navigation.*

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Gauge
import com.composables.icons.lucide.Lucide
import com.composables.ui.components.*
import com.composables.ui.theme.*
import kinuvo.protocol.usage.RequestMetrics
import kinuvo.protocol.usage.Usage
import kinuvo.ui.design.*
import kotlin.math.roundToInt

@Composable
internal fun ChatStatisticsContent(usage: Usage?, measurements: List<RequestMetrics>, modifier: Modifier = Modifier) {
    LazyColumn(modifier.heightIn(max = 560.dp), contentPadding = PaddingValues(Spacing.space12), verticalArrangement = Arrangement.spacedBy(Spacing.space12)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.space10)) {
                Text("Statistics", style = TextStyles.title)
                Text(usage?.let { "${it.provider} usage${it.plan?.let { plan -> " · $plan" }.orEmpty()}" } ?: "Provider usage", style = TextStyles.caption, color = color(mutedColor))
                if (usage == null || usage.windows.isEmpty()) Text("Usage limits not reported", style = TextStyles.caption, color = color(mutedColor))
                usage?.windows?.forEach { window ->
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.space6)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            val label = if (window.windowMinutes >= 1440) "${window.windowMinutes / 1440}d" else "${window.windowMinutes / 60}h"
                            Text(label, Modifier.weight(1f), style = TextStyles.caption)
                            Text("${window.usedPercent.roundToInt()}% used", style = TextStyles.caption, color = color(mutedColor))
                        }
                        ProgressIndicator(
                            (window.usedPercent / 100).toFloat().coerceIn(0f, 1f), Modifier.fillMaxWidth(), height = 4.dp,
                            indicatorColor = if (window.usedPercent >= 90) color(destructiveColor) else color(accentColor),
                        )
                    }
                }
                HorizontalSeparator()
                Text("Recent requests · ${measurements.size}", style = TextStyles.secondary)
                Text("From the loaded Session history, newest first.", style = TextStyles.overline, color = color(mutedColor))
                if (measurements.isEmpty()) Text("Request statistics appear after a request finishes.", style = TextStyles.caption, color = color(mutedColor))
            }
        }
        itemsIndexed(measurements, key = { _, metrics -> metrics.id }) { index, metrics ->
            RequestMetricsView(metrics, initiallyExpanded = index == 0)
        }
    }
}
