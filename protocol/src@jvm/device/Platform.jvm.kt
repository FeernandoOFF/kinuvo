package kinuvo.protocol.device

actual val currentPlatform: Platform = Platform.fromName(System.getProperty("os.name").orEmpty())
