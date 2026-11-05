package kinuvo.harness.litert

import android.os.Build
import java.io.File

internal actual fun deviceMemory(): Long? = runCatching {
    File("/proc/meminfo").useLines { lines -> lines.first { it.startsWith("MemTotal:") }.split(Regex("\\s+"))[1].toLong() * 1024 }
}.getOrNull()

internal actual fun unsupportedReason(): String? =
    if ("arm64-v8a" in Build.SUPPORTED_ABIS || "x86_64" in Build.SUPPORTED_ABIS) null else "On-device models need a 64-bit phone"
