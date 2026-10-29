package kinuvo.ui.session

import kinuvo.app.connection.*
import kinuvo.ui.agent.*
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
import kinuvo.protocol.RequestMetrics
import kinuvo.protocol.Usage
import kinuvo.ui.design.*
import kotlin.math.roundToInt

@Composable
internal fun ChatStatisticsContent(usage: Usage?, measurements: List<RequestMetrics>, modifier: Modifier = Modifier) {
    LazyColumn(modifier.heightIn(max = 560.dp), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Statistics", fontSize = 16.sp)
                Text(usage?.let { "${it.provider} usage${it.plan?.let { plan -> " · $plan" }.orEmpty()}" } ?: "Provider usage", fontSize = 12.sp, color = color(mutedColor))
                if (usage == null || usage.windows.isEmpty()) Text("Usage limits not reported", fontSize = 12.sp, color = color(mutedColor))
                usage?.windows?.forEach { window ->
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            val label = if (window.windowMinutes >= 1440) "${window.windowMinutes / 1440}d" else "${window.windowMinutes / 60}h"
                            Text(label, Modifier.weight(1f), fontSize = 12.sp)
                            Text("${window.usedPercent.roundToInt()}% used", fontSize = 12.sp, color = color(mutedColor))
                        }
                        ProgressIndicator(
                            (window.usedPercent / 100).toFloat().coerceIn(0f, 1f), Modifier.fillMaxWidth(), height = 4.dp,
                            indicatorColor = if (window.usedPercent >= 90) color(destructiveColor) else color(accentColor),
                        )
                    }
                }
                HorizontalSeparator()
                Text("Recent requests · ${measurements.size}", fontSize = 13.sp)
                Text("From the loaded Session history, newest first.", fontSize = 11.sp, color = color(mutedColor))
                if (measurements.isEmpty()) Text("Request statistics appear after a request finishes.", fontSize = 12.sp, color = color(mutedColor))
            }
        }
        itemsIndexed(measurements, key = { _, metrics -> metrics.id }) { index, metrics ->
            RequestMetricsView(metrics, initiallyExpanded = index == 0)
        }
    }
}
