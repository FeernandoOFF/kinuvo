package kinuvo.harness.server

import kinuvo.protocol.UploadAttachmentParams
import kinuvo.protocol.attachmentMimeType
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.awt.geom.AffineTransform
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.io.encoding.Base64

internal fun prepareAttachment(p: UploadAttachmentParams): UploadAttachmentParams {
    val mime = attachmentMimeType(p.name) ?: return p
    if (!mime.startsWith("image/") || mime == "image/webp") return p
    require(p.base64.length <= (kinuvo.protocol.MAX_ATTACHMENT_BYTES + 2) / 3 * 4) { "Files must be 10 MB or smaller" }
    val bytes = Base64.decode(p.base64)
    return ImageIO.createImageInputStream(ByteArrayInputStream(bytes)).use { source ->
        val reader = ImageIO.getImageReaders(source).asSequence().firstOrNull() ?: error("Couldn't read image")
        try {
            reader.input = source
            val width = reader.getWidth(0)
            val height = reader.getHeight(0)
            require(width > 0 && height > 0 && width.toLong() * height <= 40_000_000) { "Image exceeds 40 megapixels" }
            val sample = (maxOf(width, height) / 2048).coerceAtLeast(1)
            val param = reader.defaultReadParam.apply { setSourceSubsampling(sample, sample, 0, 0) }
            val image = reader.read(0, param)
            val orientation = exifOrientation(bytes)
            val rotated = orientation >= 5
            val orientedWidth = if (rotated) image.height else image.width
            val orientedHeight = if (rotated) image.width else image.height
            val scale = minOf(1.0, 2048.0 / maxOf(orientedWidth, orientedHeight))
            val output = BufferedImage((orientedWidth * scale).toInt().coerceAtLeast(1), (orientedHeight * scale).toInt().coerceAtLeast(1), BufferedImage.TYPE_INT_RGB)
            output.createGraphics().apply {
                color = Color.WHITE
                fillRect(0, 0, output.width, output.height)
                setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
                scale(scale, scale)
                val w = image.width.toDouble()
                val h = image.height.toDouble()
                transform(when (orientation) {
                    2 -> AffineTransform(-1.0, 0.0, 0.0, 1.0, w, 0.0)
                    3 -> AffineTransform(-1.0, 0.0, 0.0, -1.0, w, h)
                    4 -> AffineTransform(1.0, 0.0, 0.0, -1.0, 0.0, h)
                    5 -> AffineTransform(0.0, 1.0, 1.0, 0.0, 0.0, 0.0)
                    6 -> AffineTransform(0.0, 1.0, -1.0, 0.0, h, 0.0)
                    7 -> AffineTransform(0.0, -1.0, -1.0, 0.0, h, w)
                    8 -> AffineTransform(0.0, -1.0, 1.0, 0.0, 0.0, w)
                    else -> AffineTransform()
                })
                drawImage(image, 0, 0, null)
                dispose()
            }
            val encoded = ByteArrayOutputStream().also { ImageIO.write(output, "jpeg", it) }.toByteArray()
            p.copy(name = p.name.substringBeforeLast('.') + ".jpg", base64 = Base64.encode(encoded))
        } finally { reader.dispose() }
    }
}

private fun exifOrientation(bytes: ByteArray): Int = runCatching {
    fun unsigned(at: Int) = bytes[at].toInt() and 255
    if (unsigned(0) != 255 || unsigned(1) != 216) return@runCatching 1
    var at = 2
    while (at + 4 <= bytes.size && unsigned(at) == 255) {
        val marker = unsigned(at + 1)
        if (marker == 218 || marker == 217) break
        val length = unsigned(at + 2) * 256 + unsigned(at + 3)
        if (length < 2 || at + 2 + length > bytes.size) break
        if (marker == 225 && length >= 16 && bytes.copyOfRange(at + 4, at + 10).decodeToString() == "Exif\u0000\u0000") {
            val tiff = ByteBuffer.wrap(bytes, at + 10, length - 8).slice()
            tiff.order(if (tiff.get(0) == 73.toByte() && tiff.get(1) == 73.toByte()) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN)
            if (tiff.getShort(2).toInt() != 42) return@runCatching 1
            val directory = tiff.getInt(4)
            val count = (tiff.getShort(directory).toInt() and 65535).coerceAtMost(1024)
            for (i in 0 until count) {
                val entry = directory + 2 + i * 12
                if ((tiff.getShort(entry).toInt() and 65535) == 274 && tiff.getShort(entry + 2).toInt() == 3 && tiff.getInt(entry + 4) == 1)
                    return@runCatching tiff.getShort(entry + 8).toInt().takeIf { it in 1..8 } ?: 1
            }
        }
        at += length + 2
    }
    1
}.getOrDefault(1)
