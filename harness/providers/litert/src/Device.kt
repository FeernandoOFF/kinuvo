package kinuvo.harness.litert

import java.io.File

/** This machine's total RAM in bytes, or null when unknown. */
internal expect fun deviceMemory(): Long?

/** Why LiteRT-LM can't run on this machine, or null when it can. */
internal expect fun unsupportedReason(): String?

internal interface NativeEngine {
    val bytes: Long
    val installed: Boolean
    suspend fun ensure(progress: (Long) -> Unit)
    fun load()
}
internal expect fun nativeEngine(home: File): NativeEngine
