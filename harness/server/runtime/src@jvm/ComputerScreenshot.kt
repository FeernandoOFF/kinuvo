package kinuvo.harness.server

import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

internal actual fun cropComputerScreenshot(bytes: ByteArray, x: Int, y: Int, width: Int, height: Int): ByteArray {
    val image = requireNotNull(ImageIO.read(bytes.inputStream())) { "Unreadable screenshot" }
    return ByteArrayOutputStream().also { ImageIO.write(image.getSubimage(x, y, width, height), "jpeg", it) }.toByteArray()
}
