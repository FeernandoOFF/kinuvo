package kinuvo.harness.server.screens

internal expect fun cropComputerScreenshot(bytes: ByteArray, x: Int, y: Int, width: Int, height: Int): ByteArray
