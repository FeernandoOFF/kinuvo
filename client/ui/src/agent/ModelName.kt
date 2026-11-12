package kinuvo.ui.agent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import kinuvo.app.agent.label
import kinuvo.protocol.ModelInfo
import kinuvo.ui.design.Badge
import com.composables.ui.components.Text

/** A model's name with its tier beside it. */
@Composable
fun ModelName(model: ModelInfo) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(model.name, Modifier.weight(1f, fill = false), singleLine = true, overflow = TextOverflow.Ellipsis)
        model.tier?.let { Badge(it.label) }
    }
}
