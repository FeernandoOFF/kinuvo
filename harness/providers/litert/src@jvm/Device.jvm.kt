package kinuvo.harness.litert

import java.lang.management.ManagementFactory

internal actual fun deviceMemory(): Long? = (ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean)?.totalMemorySize

internal actual fun unsupportedReason(): String? {
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase().let { if (it == "amd64") "x86_64" else if (it == "arm64") "aarch64" else it }
    val supported = when {
        os.startsWith("mac") -> arch == "aarch64"
        os.startsWith("linux") -> arch in setOf("x86_64", "aarch64")
        os.startsWith("windows") -> arch == "x86_64"
        else -> false
    }
    return if (supported) null else "On-device models need an Apple silicon Mac, or 64-bit Windows or Linux"
}
