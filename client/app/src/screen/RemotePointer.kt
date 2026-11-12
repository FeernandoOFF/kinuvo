package kinuvo.app.screen

import kinuvo.protocol.screen.ScreenAction
import kinuvo.protocol.screen.ScreenActionKind
import kotlin.math.roundToInt

/** Normalized position on the source image, independent of its delivery resolution. */
data class RemotePointer(val x: Float = 0.5f, val y: Float = 0.5f) {
    fun move(dx: Float, dy: Float, width: Float, height: Float, zoom: Float = 1f): RemotePointer = copy(
        x = (x + dx / (width.coerceAtLeast(1f) * zoom)).coerceIn(0f, 1f),
        y = (y + dy / (height.coerceAtLeast(1f) * zoom)).coerceIn(0f, 1f))

    fun followingPan(imageSize: Float, surfaceSize: Float, zoom: Float, horizontal: Boolean): Float {
        val limit = ((imageSize * zoom - surfaceSize) / 2).coerceAtLeast(0f)
        val position = if (horizontal) x else y
        return (-(position - 0.5f) * imageSize * zoom).coerceIn(-limit, limit)
    }

    fun tap(width: Int, height: Int) = ScreenAction(ScreenActionKind.Tap,
        x = (x * width).roundToInt().coerceIn(0, width - 1), y = (y * height).roundToInt().coerceIn(0, height - 1))

    companion object {
        fun at(viewX: Float, viewY: Float, surfaceWidth: Float, surfaceHeight: Float,
            imageWidth: Float, imageHeight: Float, zoom: Float, panX: Float, panY: Float): RemotePointer? {
            val x = (viewX - surfaceWidth / 2 - panX) / zoom / imageWidth + 0.5f
            val y = (viewY - surfaceHeight / 2 - panY) / zoom / imageHeight + 0.5f
            return if (x in 0f..1f && y in 0f..1f) RemotePointer(x, y) else null
        }
        fun centered(width: Float, height: Float, zoom: Float, panX: Float, panY: Float) = RemotePointer(
            (0.5f - panX / width.coerceAtLeast(1f) / zoom).coerceIn(0f, 1f),
            (0.5f - panY / height.coerceAtLeast(1f) / zoom).coerceIn(0f, 1f))
    }
}
