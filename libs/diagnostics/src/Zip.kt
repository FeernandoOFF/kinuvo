package kinuvo.diagnostics

import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlinx.io.writeIntLe
import kotlinx.io.writeShortLe

/** Writes an uncompressed ZIP archive; the files are small, bounded text, and iOS has no `java.util.zip`. */
internal class ZipWriter {
    private val body = Buffer()
    private val directory = Buffer()
    private var entries = 0

    fun add(name: String, data: ByteArray) {
        val label = name.encodeToByteArray()
        val crc = crc32(data)
        val offset = body.size
        body.writeIntLe(LOCAL_HEADER)
        body.writeShortLe(VERSION)
        body.writeShortLe(UTF8_NAMES)
        body.writeShortLe(STORED)
        body.writeShortLe(TIME)
        body.writeShortLe(DATE)
        body.writeIntLe(crc)
        body.writeIntLe(data.size)
        body.writeIntLe(data.size)
        body.writeShortLe(label.size.toShort())
        body.writeShortLe(0)
        body.write(label)
        body.write(data)
        directory.writeIntLe(CENTRAL_HEADER)
        directory.writeShortLe(VERSION)
        directory.writeShortLe(VERSION)
        directory.writeShortLe(UTF8_NAMES)
        directory.writeShortLe(STORED)
        directory.writeShortLe(TIME)
        directory.writeShortLe(DATE)
        directory.writeIntLe(crc)
        directory.writeIntLe(data.size)
        directory.writeIntLe(data.size)
        directory.writeShortLe(label.size.toShort())
        repeat(4) { directory.writeShortLe(0) }
        directory.writeIntLe(0)
        directory.writeIntLe(offset.toInt())
        directory.write(label)
        entries++
    }

    fun finish(): ByteArray {
        val start = body.size
        val size = directory.size
        body.write(directory.readByteArray())
        body.writeIntLe(END_HEADER)
        body.writeShortLe(0)
        body.writeShortLe(0)
        body.writeShortLe(entries.toShort())
        body.writeShortLe(entries.toShort())
        body.writeIntLe(size.toInt())
        body.writeIntLe(start.toInt())
        body.writeShortLe(0)
        return body.readByteArray()
    }

    private companion object {
        const val LOCAL_HEADER = 0x04034b50
        const val CENTRAL_HEADER = 0x02014b50
        const val END_HEADER = 0x06054b50
        const val VERSION: Short = 20
        const val UTF8_NAMES: Short = 0x0800
        const val STORED: Short = 0
        // 1 January 2026, 00:00; the files' real times are in system.json.
        const val TIME: Short = 0
        const val DATE: Short = (((2026 - 1980) shl 9) or (1 shl 5) or 1).toShort()
    }
}

private val crcTable = IntArray(256) { index ->
    var c = index
    repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0xEDB88320.toInt() else c ushr 1 }
    c
}

internal fun crc32(data: ByteArray): Int {
    var c = -1
    for (byte in data) c = crcTable[(c xor byte.toInt()) and 0xFF] xor (c ushr 8)
    return c.inv()
}
