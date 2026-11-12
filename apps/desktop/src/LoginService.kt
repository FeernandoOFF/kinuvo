package kinuvo.desktop

import io.github.oshai.kotlinlogging.KotlinLogging
import kinuvo.server.jvm.LocalServer
import kinuvo.server.jvm.LoginServiceSettings
import kinuvo.server.jvm.ServeException
import kinuvo.server.jvm.kinuvoHome
import kinuvo.server.jvm.serve
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileOutputStream
import java.io.PrintStream
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT

/** Mirrors `SMAppService.Status`. */
internal enum class AgentStatus { NotRegistered, Enabled, RequiresApproval, NotFound;
    val word get() = name.replaceFirstChar { it.lowercase() }
}

/** The launch agent bundled in `Kinuvo.app`, registered with `SMAppService` so the Server runs under the app's identity. */
internal object ServiceAgent {
    private val symbols by lazy {
        val resources = System.getProperty("compose.application.resources.dir")?.let(::File)
            ?: File(generateSequence(File(".").canonicalFile) { it.parentFile }.firstOrNull { File(it, "project.yaml").isFile } ?: File("."), "build")
        SymbolLookup.libraryLookup(File(resources, "libKinuvoService.dylib").toPath(), Arena.global())
    }
    private fun function(name: String, descriptor: FunctionDescriptor) = Linker.nativeLinker().downcallHandle(symbols.find("kinuvo_service_$name").orElseThrow(), descriptor)
    private val status by lazy { function("status", FunctionDescriptor.of(JAVA_INT)) }
    private val register by lazy { function("register", FunctionDescriptor.of(ADDRESS)) }
    private val unregister by lazy { function("unregister", FunctionDescriptor.of(ADDRESS)) }
    private val free by lazy { function("free", FunctionDescriptor.ofVoid(ADDRESS)) }

    /** Null outside the packaged app, where the helper library is missing. */
    fun status(): AgentStatus? = runCatching { AgentStatus.entries[status.invokeWithArguments() as Int] }.getOrNull()

    /** An error message, or null once done. */
    fun register(): String? = message(register.invokeWithArguments() as MemorySegment)

    fun unregister(): String? = message(unregister.invokeWithArguments() as MemorySegment)

    private fun message(text: MemorySegment): String? {
        if (text == MemorySegment.NULL) return null
        return try { text.reinterpret(Long.MAX_VALUE).getString(0) } finally { free.invokeWithArguments(text) }
    }
}

/** `Kinuvo --service status|register|unregister`: what `kinuvo service` runs, as SMAppService answers only to the app itself. */
internal fun runServiceCommand(command: String?): Int {
    System.setProperty("java.awt.headless", "true")
    val error = try {
        when (command) {
            "status" -> ServiceAgent.status().let { if (it == null) "The service helper is missing from this app" else { println(it.word); null } }
            "register" -> ServiceAgent.register()
            "unregister" -> ServiceAgent.unregister()
            else -> "Unknown service command: $command"
        }
    } catch (e: Throwable) {
        e.message ?: e.toString()
    }
    error?.let { System.err.println(it) }
    return if (error == null) 0 else 1
}

/** `Kinuvo --serve`: only the Server, with the settings `kinuvo service install` left, until macOS stops it. */
internal fun serveFromLogin(): Int {
    System.setProperty("java.awt.headless", "true")
    val settings = LoginServiceSettings.read() ?: LoginServiceSettings(kinuvoHome().toString())
    System.setProperty("kinuvo.home", settings.home)
    val logs = File(settings.home, "logs").apply { mkdirs() }
    System.setProperty("logback.configurationFile", "logback-service.xml")
    System.setProperty("kinuvo.log.file", File(logs, "server.log").path)
    PrintStream(FileOutputStream(File(logs, "service-output.log"), true), true).let { System.setOut(it); System.setErr(it) }
    val log = KotlinLogging.logger("kinuvo.desktop")
    return try {
        runBlocking {
            var waiting = false
            while (true) {
                val other = LocalServer.read(kinuvoHome())?.takeIf { it.alive && it.pid != ProcessHandle.current().pid() } ?: break
                if (!waiting) log.info { "Another Server (pid ${other.pid}) runs on ${settings.home}; waiting for it to stop." }
                waiting = true
                delay(5_000)
            }
            serve(settings.options)
        }
        0
    } catch (e: ServeException) {
        log.error { e.message }
        1
    }
}
