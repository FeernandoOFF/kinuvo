package kinuvo.ui

import android.content.Context
import android.graphics.Rect
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.ComposeView

/** Shows [KinuvoApp] as the activity's content. */
fun setKinuvoContent(activity: ComponentActivity) =
    activity.setContentView(TouchFocusLayout(activity).apply { addView(ComposeView(activity).apply { setContent { KinuvoApp() } }) })

/**
 * Ignores focus the window hands down in touch mode. Android does that when a focused view is removed, such as an HTML
 * Surface's WebView leaving the chat mid-layout, and Compose crashes searching for focus during measure.
 */
private class TouchFocusLayout(context: Context) : FrameLayout(context) {
    override fun onRequestFocusInDescendants(direction: Int, previouslyFocusedRect: Rect?) =
        !isInTouchMode && super.onRequestFocusInDescendants(direction, previouslyFocusedRect)
}
