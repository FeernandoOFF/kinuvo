package kinuvo.tailcat

import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT
import java.nio.file.Files

private const val ABI = 3

internal actual val blockingDispatcher: CoroutineDispatcher = Dispatchers.IO

internal actual fun nativeCall(): (String, String) -> String = try { load() } catch (e: LinkageError) { throw IllegalStateException("Tailcat native library could not be loaded", e) }

private fun load(): (String, String) -> String {
    val symbols = SymbolLookup.libraryLookup(library().toPath(), Arena.global())
    fun function(name: String, descriptor: FunctionDescriptor) =
        Linker.nativeLinker().downcallHandle(symbols.find("kinuvo_tailcat_$name").orElseThrow(), descriptor)
    val call = function("call", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS))
    val free = function("free", FunctionDescriptor.ofVoid(ADDRESS))

    check(function("abi_version", FunctionDescriptor.of(JAVA_INT)).invokeWithArguments() == ABI) { "Unsupported Tailcat ABI" }

    return { method, params -> Arena.ofConfined().use { arena ->
        val result = call.invokeWithArguments(arena.allocateFrom(method), arena.allocateFrom(params)) as MemorySegment
        try { result.reinterpret(Long.MAX_VALUE).getString(0) } finally { free.invokeWithArguments(result) }
    } }
}

internal actual fun onShutdown(action: () -> Unit) { Runtime.getRuntime().addShutdownHook(Thread(action)) }

private fun library(): File {
    val os = System.getProperty("os.name")
    val arch = System.getProperty("os.arch")
    val cpu = when (arch) {
        "aarch64", "arm64" -> "arm64"
        "amd64", "x86_64" -> "x64"
        else -> null
    }
    val (platform, name) = when {
        cpu != null && os.startsWith("Mac") -> "macos-$cpu" to "libKinuvoTailcat.dylib"
        cpu != null && os == "Linux" -> "linux-$cpu" to "libKinuvoTailcat.so"
        cpu == "x64" && os.startsWith("Windows") -> "windows-x64" to "KinuvoTailcat.dll"
        else -> error("Tailcat is unavailable on $os ($arch)")
    }
    val root = generateSequence(File(".").canonicalFile) { it.parentFile }.firstOrNull { File(it, "project.yaml").isFile }
    val jar = runCatching { File(Tailcat::class.java.protectionDomain.codeSource.location.toURI()).parentFile }.getOrNull()
        ?: File(System.getProperty("java.class.path")).takeIf { it.extension == "jar" && it.isFile }?.absoluteFile?.parentFile
    val found = listOfNotNull(
        System.getProperty("kinuvo.tailcat.library")?.let(::File),
        System.getProperty("compose.application.resources.dir")?.let { File(it, name) },
        jar?.let { File(it, name) },
        root?.let { File(it, "libs/tailcat/prebuilt/$platform/$name") },
    ).firstOrNull { it.isFile }
    if (found != null) return found
    Tailcat::class.java.getResourceAsStream("/natives/$platform/$name")?.use { input ->
        val extracted = Files.createTempFile("kinuvo-tailcat-", ".${name.substringAfterLast('.')}").toFile()
        extracted.deleteOnExit()
        extracted.outputStream().use { input.copyTo(it) }
        return extracted
    }
    throw IllegalStateException("Tailcat library unavailable; reinstall a packaged release")
}
