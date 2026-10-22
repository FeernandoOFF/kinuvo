package kinuvo.harness.server

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream

internal actual fun cropComputerScreenshot(bytes: ByteArray, x: Int, y: Int, width: Int, height: Int): ByteArray {
    val image = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size)) { "Unreadable screenshot" }
    val crop = Bitmap.createBitmap(image, x, y, width, height)
    return try {
        ByteArrayOutputStream().also { check(crop.compress(Bitmap.CompressFormat.JPEG, 90, it)) }.toByteArray()
    } finally {
        if (crop !== image) crop.recycle()
        image.recycle()
    }
}
