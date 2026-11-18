package kinuvo.protocol.device

actual val currentArch: Arch = Arch.fromName(System.getProperty("os.arch").orEmpty())
