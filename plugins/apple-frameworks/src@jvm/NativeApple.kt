package kinuvo.plugins.apple

import kinuvo.protocol.device.Platform
import kinuvo.protocol.device.currentPlatform
import java.io.File
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS

private class NativeAppleSystem(library: File) : AppleSystem {
    private val symbols = SymbolLookup.libraryLookup(library.toPath(), Arena.global())
    private fun function(name: String, descriptor: FunctionDescriptor) = Linker.nativeLinker().downcallHandle(symbols.find("kinuvo_apple_$name").orElseThrow(), descriptor)
    private val call = function("call", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS))
    private val free = function("free", FunctionDescriptor.ofVoid(ADDRESS))

    override fun call(method: String, params: String): String = Arena.ofConfined().use { arena ->
        val result = call.invokeWithArguments(arena.allocateFrom(method), arena.allocateFrom(params)) as MemorySegment
        try { result.reinterpret(Long.MAX_VALUE).getString(0) } finally { free.invokeWithArguments(result) }
    }
}

/** Where the desktop app's bundle keeps `libKinuvoApple.dylib`, or the checkout's `build/` when run from source. */
private fun libraries(): List<File> {
    val name = "libKinuvoApple.dylib"
    val root = generateSequence(File(".").canonicalFile) { it.parentFile }.firstOrNull { File(it, "project.yaml").isFile }
    return listOfNotNull(System.getProperty("kinuvo.apple.library")?.let(::File), System.getProperty("compose.application.resources.dir")?.let { File(it, name) }, root?.let { File(it, "build/$name") })
}

/**
 * The Apple frameworks of this host: the Swift library the desktop app bundles, called in-process so the app, not a helper,
 * holds the Reminders and Calendar permissions. Anywhere else (the `kinuvo` command, a service, another OS) it can't reach them.
 */
fun loadAppleSystem(): AppleSystem {
    if (currentPlatform != Platform.MacOS) return UnavailableAppleSystem()
    val library = libraries().firstOrNull { it.isFile } ?: return UnavailableAppleSystem()
    return runCatching { NativeAppleSystem(library) }.getOrElse { UnavailableAppleSystem() }
}
