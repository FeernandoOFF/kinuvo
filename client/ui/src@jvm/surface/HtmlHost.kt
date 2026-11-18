package kinuvo.ui.surface

import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.serialization.json.JsonObject

internal interface HtmlHost : AutoCloseable {
    fun send(command: JsonObject)
    fun poll(): String?
    fun image(): ImageBitmap?
}
