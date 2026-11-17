package kinuvo.tailcat

import java.io.File
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT
import java.nio.file.Files

internal actual fun nativeCall(): (String, String) -> String {
    val symbols = SymbolLookup.libraryLookup(library().toPath(), Arena.global())
    fun function(name: String, descriptor: FunctionDescriptor) =
        Linker.nativeLinker().downcallHandle(symbols.find("kinuvo_tailcat_$name").orElseThrow(), descriptor)
    val call = function("call", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS))
    val free = function("free", FunctionDescriptor.ofVoid(ADDRESS))

    check(function("abi_version", FunctionDescriptor.of(JAVA_INT)).invokeWithArguments() == 1) { "Unsupported Tailcat ABI" }

    return { method, params -> Arena.ofConfined().use { arena ->
        val result = call.invokeWithArguments(arena.allocateFrom(method), arena.allocateFrom(params)) as MemorySegment
        try { result.reinterpret(Long.MAX_VALUE).getString(0) } finally { free.invokeWithArguments(result) }
    } }
}

private fun library(): File {
    val suffix = when {
        System.getProperty("os.name").startsWith("Mac") -> "dylib"
        System.getProperty("os.name").startsWith("Linux") -> "so"
        else -> throw IllegalStateException("Tailcat supports macOS and Linux")
    }
    val name = "libKinuvoTailcat.$suffix"
    val root = generateSequence(File(".").canonicalFile) { it.parentFile }.firstOrNull { File(it, "project.yaml").isFile }
    val jar = runCatching { File(Tailcat::class.java.protectionDomain.codeSource.location.toURI()).parentFile }.getOrNull()
    val found = listOfNotNull(
        System.getProperty("kinuvo.tailcat.library")?.let(::File),
        System.getProperty("compose.application.resources.dir")?.let { File(it, name) },
        jar?.let { File(it, name) },
        root?.let { File(it, "build/$name") },
    ).firstOrNull { it.isFile }
    if (found != null) return found
    val os = if (suffix == "dylib") "macos" else "linux"
    val arch = if (System.getProperty("os.arch") in setOf("aarch64", "arm64")) "arm64" else "x64"
    Tailcat::class.java.getResourceAsStream("/natives/$os-$arch/$name")?.use { input ->
        val extracted = Files.createTempFile("kinuvo-tailcat-", ".$suffix").toFile()
        extracted.deleteOnExit()
        extracted.outputStream().use { input.copyTo(it) }
        return extracted
    }
    throw IllegalStateException("Tailcat library unavailable; run scripts/build-tailcat.sh or install a packaged release")
}
