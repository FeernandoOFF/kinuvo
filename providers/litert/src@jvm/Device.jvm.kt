package kinuvo.providers.litert

import java.lang.management.ManagementFactory
import java.io.File
import kinuvo.server.plugin.models.liteRtEngine

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

internal actual fun nativeEngine(home: File): NativeEngine = object : NativeEngine {
    private val engine by lazy { if (unsupportedReason() == null) liteRtEngine(home) else null }
    override val bytes get() = engine?.download?.bytes ?: 0L
    override val installed get() = engine?.installed == true
    override suspend fun ensure(progress: (Long) -> Unit) = requireNotNull(engine) { unsupportedReason().orEmpty() }.ensure(progress)
    override fun load() {
        check(installed) { "Download the on-device engine in Settings → Model providers" }
        System.load(File(requireNotNull(engine).folder, if (System.getProperty("os.name").startsWith("Windows")) "litertlm_jni.dll" else "liblitertlm_jni.so").absolutePath)
    }
}
