package kinuvo.providers.litert

import kinuvo.protocol.device.Arch
import kinuvo.protocol.device.Platform
import kinuvo.protocol.device.currentArch
import kinuvo.protocol.device.currentPlatform
import java.lang.management.ManagementFactory
import java.io.File
import kinuvo.server.plugin.models.liteRtEngine

internal actual fun deviceMemory(): Long? = (ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean)?.totalMemorySize

internal actual fun unsupportedReason(): String? {
    val supported = when (currentPlatform) {
        Platform.MacOS -> currentArch == Arch.Arm64
        Platform.Linux -> currentArch in setOf(Arch.X64, Arch.Arm64)
        Platform.Windows -> currentArch == Arch.X64
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
        System.load(File(requireNotNull(engine).folder, if (currentPlatform == Platform.Windows) "litertlm_jni.dll" else "liblitertlm_jni.so").absolutePath)
    }
}
