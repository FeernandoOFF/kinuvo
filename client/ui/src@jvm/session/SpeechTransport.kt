package kinuvo.ui.session

import kinuvo.protocol.Platform
import kinuvo.protocol.currentPlatform
import java.io.File
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS

internal interface SpeechTransport : AutoCloseable {
    fun send(command: String)
    fun poll(): String?
}

internal fun desktopSpeechTransport(): SpeechTransport = if (currentPlatform == Platform.MacOS) NativeSpeechTransport() else WindowsSpeechTransport()

internal fun speechResources(): File {
    System.getProperty("compose.application.resources.dir")?.let { return File(it) }
    val root = generateSequence(File(".").canonicalFile) { it.parentFile }.firstOrNull { File(it, "project.yaml").isFile }
    return File(root ?: File("."), "build")
}

private class NativeSpeechTransport : SpeechTransport {
    private val arena = Arena.global()
    private val symbols = SymbolLookup.libraryLookup(File(speechResources(), "libKinuvoSpeech.dylib").toPath(), arena)
    private fun function(name: String, descriptor: FunctionDescriptor) = Linker.nativeLinker().downcallHandle(symbols.find("kinuvo_speech_$name").orElseThrow(), descriptor)
    private val create = function("create", FunctionDescriptor.of(ADDRESS))
    private val send = function("send", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS))
    private val poll = function("poll", FunctionDescriptor.of(ADDRESS, ADDRESS))
    private val free = function("free", FunctionDescriptor.ofVoid(ADDRESS))
    private val close = function("close", FunctionDescriptor.ofVoid(ADDRESS))
    private var handle = create.invokeWithArguments() as MemorySegment
    @Synchronized override fun send(command: String) {
        check(handle != MemorySegment.NULL) { "Desktop speech is closed" }
        Arena.ofConfined().use { send.invokeWithArguments(handle, it.allocateFrom(command)) }
    }
    @Synchronized override fun poll(): String? {
        check(handle != MemorySegment.NULL) { "Desktop speech is closed" }
        val event = poll.invokeWithArguments(handle) as MemorySegment
        if (event == MemorySegment.NULL) return null
        return try { event.reinterpret(Long.MAX_VALUE).getString(0) } finally { free.invokeWithArguments(event) }
    }
    @Synchronized override fun close() {
        if (handle == MemorySegment.NULL) return
        close.invokeWithArguments(handle)
        handle = MemorySegment.NULL
        // AppKit may still have queued blocks and AVFoundation callbacks in this library.
        // Keep its shared arena loaded for the lifetime of the JVM.
    }
}

private class WindowsSpeechTransport : SpeechTransport {
    private val process = ProcessBuilder(System.getenv("KINUVO_SPEECH_HELPER") ?: File(speechResources(), "KinuvoSpeech.exe").path)
        .redirectError(ProcessBuilder.Redirect.INHERIT).start()
    private val input = process.inputStream.bufferedReader()
    override fun send(command: String) { process.outputStream.run { write((command + "\n").toByteArray(Charsets.UTF_8)); flush() } }
    override fun poll(): String? {
        check(process.isAlive) { "Desktop speech stopped. Try again." }
        return if (input.ready()) input.readLine() else null
    }
    override fun close() { process.destroy() }
}
