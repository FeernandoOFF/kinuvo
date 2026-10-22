@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package kinuvo.harness.server

import kotlinx.cinterop.*
import platform.CoreGraphics.*
import platform.Foundation.*
import platform.UIKit.*

internal actual fun cropComputerScreenshot(bytes: ByteArray, x: Int, y: Int, width: Int, height: Int): ByteArray {
    val data = bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong()) }
    val image = requireNotNull(UIImage(data = data).CGImage) { "Unreadable screenshot" }
    val crop = requireNotNull(CGImageCreateWithImageInRect(image, CGRectMake(x.toDouble(), y.toDouble(), width.toDouble(), height.toDouble())))
    return try {
        val encoded = requireNotNull(UIImageJPEGRepresentation(UIImage.imageWithCGImage(crop), 0.9))
        encoded.bytes!!.reinterpret<ByteVar>().readBytes(encoded.length.toInt())
    } finally { CGImageRelease(crop) }
}
