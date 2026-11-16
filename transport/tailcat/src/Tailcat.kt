package kinuvo.transport.tailcat

import kotlinx.serialization.json.*
import java.io.File
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ConcurrentHashMap
import java.nio.file.Files

interface Tailcat {
    fun serve(state: File, port: Int): Tunnel
    fun forward(address: String): Tunnel
    fun reset(state: File)

    interface Tunnel : AutoCloseable {
        val address: String?
        val port: Int?
    }

    companion object {
        private val native by lazy { NativeTailcat() }
        fun load(): Tailcat = native
    }
}

private class NativeTailcat : Tailcat {
    private val tunnels = ConcurrentHashMap.newKeySet<Tailcat.Tunnel>()
    private val symbols = SymbolLookup.libraryLookup(library().toPath(), Arena.global())
    private fun function(name: String, descriptor: FunctionDescriptor) =
        Linker.nativeLinker().downcallHandle(symbols.find("kinuvo_tailcat_$name").orElseThrow(), descriptor)
    private val call = function("call", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS))
    private val free = function("free", FunctionDescriptor.ofVoid(ADDRESS))

    init {
        check(function("abi_version", FunctionDescriptor.of(JAVA_INT)).invokeWithArguments() == 1) { "Unsupported Tailcat ABI" }
        Runtime.getRuntime().addShutdownHook(Thread { tunnels.forEach { runCatching { it.close() } } })
    }

    private fun call(method: String, params: JsonObject): JsonObject = Arena.ofConfined().use { arena ->
        val result = call.invokeWithArguments(arena.allocateFrom(method), arena.allocateFrom(params.toString())) as MemorySegment
        val value = try { Json.parseToJsonElement(result.reinterpret(Long.MAX_VALUE).getString(0)).jsonObject }
            finally { free.invokeWithArguments(result) }
        value["error"]?.jsonPrimitive?.content?.let { throw IllegalStateException(it) }
        value
    }

    private fun tunnel(method: String, params: JsonObject): Tailcat.Tunnel {
        val result = call(method, params)
        val handle = result.getValue("handle")
        return object : Tailcat.Tunnel {
            private val closed = AtomicBoolean()
            override val address = result["address"]?.jsonPrimitive?.content
            override val port = result["port"]?.jsonPrimitive?.int
            override fun close() {
                if (closed.compareAndSet(false, true)) {
                    try { call("stop", buildJsonObject { put("handle", handle) }) } finally { tunnels.remove(this) }
                }
            }
        }.also { tunnels.add(it) }
    }

    override fun serve(state: File, port: Int) = tunnel("serve", buildJsonObject { put("state", state.path); put("port", port) })
    override fun forward(address: String) = tunnel("forward", buildJsonObject { put("address", address) })
    override fun reset(state: File) { call("reset", buildJsonObject { put("state", state.path) }) }

    private fun library(): File {
        val suffix = when {
            System.getProperty("os.name").startsWith("Mac") -> "dylib"
            System.getProperty("os.name").startsWith("Linux") -> "so"
            else -> throw IllegalStateException("Tailcat supports macOS and Linux")
        }
        val name = "libKinuvoTailcat.$suffix"
        val root = generateSequence(File(".").canonicalFile) { it.parentFile }.firstOrNull { File(it, "project.yaml").isFile }
        val jar = runCatching { File(NativeTailcat::class.java.protectionDomain.codeSource.location.toURI()).parentFile }.getOrNull()
        val found = listOfNotNull(
            System.getProperty("kinuvo.tailcat.library")?.let(::File),
            System.getProperty("compose.application.resources.dir")?.let { File(it, name) },
            jar?.let { File(it, name) },
            root?.let { File(it, "build/$name") },
        ).firstOrNull { it.isFile }
        if (found != null) return found
        val os = if (suffix == "dylib") "macos" else "linux"
        val arch = if (System.getProperty("os.arch") in setOf("aarch64", "arm64")) "arm64" else "x64"
        NativeTailcat::class.java.getResourceAsStream("/natives/$os-$arch/$name")?.use { input ->
            val extracted = Files.createTempFile("kinuvo-tailcat-", ".$suffix").toFile()
            extracted.deleteOnExit()
            extracted.outputStream().use { input.copyTo(it) }
            return extracted
        }
        throw IllegalStateException("Tailcat library unavailable; run scripts/build-tailcat.sh or install a packaged release")
    }
}
