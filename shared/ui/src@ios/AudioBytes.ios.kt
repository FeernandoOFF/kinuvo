@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package kinuvo.ui

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.create
import platform.posix.memcpy

/** Both directions copy while pinned; neither Foundation nor Swift retains a Kotlin pointer. */
object AudioBytes {
    fun toData(bytes: ByteArray): NSData = if (bytes.isEmpty()) NSData() else bytes.usePinned {
        NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong())
    }

    fun fromData(data: NSData): ByteArray {
        require(data.length <= Int.MAX_VALUE.toULong())
        return ByteArray(data.length.toInt()).also { bytes ->
            if (bytes.isNotEmpty()) bytes.usePinned { memcpy(it.addressOf(0), data.bytes, data.length) }
        }
    }
}
