package kinuvo.protocol

actual val currentPlatform: Platform = Platform.fromName(System.getProperty("os.name").orEmpty())
