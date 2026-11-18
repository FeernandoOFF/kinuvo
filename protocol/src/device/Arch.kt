package kinuvo.protocol.device

enum class Arch(val displayName: String) {
    X64("x86_64"), Arm64("aarch64"), Unknown("Unknown");

    companion object {
        fun fromName(name: String): Arch = when (name.lowercase()) {
            "amd64", "x86_64" -> X64
            "aarch64", "arm64" -> Arm64
            else -> Unknown
        }
    }
}

expect val currentArch: Arch
