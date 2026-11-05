package kinuvo.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.progressSemantics
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.composables.ui.theme.*

/** A thin bar filled to [progress], from 0 to 1, e.g. a download. */
@Composable
fun ProgressBar(progress: Float, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(color(borderColor)).progressSemantics(progress)) {
        Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(color(accentColor)))
    }
}
