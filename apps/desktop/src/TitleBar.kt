package kinuvo.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.FrameWindowScope
import com.jetbrains.JBR
import kinuvo.ui.design.WindowChrome
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent

private const val TitleBarHeight = 52f

/**
 * Replaces the native title bar with a transparent one (JetBrains Runtime only), so content draws up to the top edge
 * next to the window buttons, ChatGPT-style. Returns null, keeping the standard title bar, on other JDKs.
 */
@Composable
fun FrameWindowScope.rememberWindowChrome(): WindowChrome? {
    val titleBar = remember(window) {
        if (!JBR.isWindowDecorationsSupported()) return@remember null
        JBR.getWindowDecorations().createCustomTitleBar().also {
            it.height = TitleBarHeight
            JBR.getWindowDecorations().setCustomTitleBar(window, it)
            window.rootPane.putClientProperty("apple.awt.windowTitleVisible", false)
        }
    } ?: return null
    val chrome = remember(titleBar) {
        val mac = System.getProperty("os.name").startsWith("Mac")
        WindowChrome(
            height = TitleBarHeight.dp,
            leadingInset = titleBar.leftInset.takeIf { it > 0 }?.dp ?: if (mac) 76.dp else 0.dp,
            trailingInset = titleBar.rightInset.dp,
        )
    }
    // The title bar drags the window unless the pointer is over a control registered with Modifier.titleBarControl().
    DisposableEffect(window, titleBar) {
        val listener = object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) = hitTest(e)
            override fun mouseReleased(e: MouseEvent) = hitTest(e)
            override fun mouseDragged(e: MouseEvent) = hitTest(e)
            override fun mouseMoved(e: MouseEvent) = hitTest(e)

            private fun hitTest(e: MouseEvent) {
                val scale = window.graphicsConfiguration?.defaultTransform
                val point = Offset((e.x * (scale?.scaleX ?: 1.0)).toFloat(), (e.y * (scale?.scaleY ?: 1.0)).toFloat())
                titleBar.forceHitTest(chrome.controls.values.any { it.contains(point) })
            }
        }
        window.addMouseListener(listener)
        window.addMouseMotionListener(listener)
        onDispose {
            window.removeMouseListener(listener)
            window.removeMouseMotionListener(listener)
        }
    }
    return chrome
}
