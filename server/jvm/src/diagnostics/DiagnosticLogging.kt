package kinuvo.server.jvm.diagnostics

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.rolling.RollingFileAppender
import ch.qos.logback.core.rolling.FixedWindowRollingPolicy
import ch.qos.logback.core.rolling.SizeBasedTriggeringPolicy
import ch.qos.logback.core.util.FileSize
import kinuvo.protocol.diagnostics.DiagnosticStatus
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import com.sun.management.HotSpotDiagnosticMXBean
import java.lang.management.ManagementFactory
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Per-process files keep a desktop, Server and CLI from rotating each other's logs. */
object DiagnosticLogging {
    private var installed = false

    @Synchronized fun install(scope: String, home: File = diagnosticHome()) {
        if (installed) return
        installed = true
        runCatching {
            val dir = File(home, "logs").also(::privateDirectory)
            val context = LoggerFactory.getILoggerFactory() as LoggerContext
            val root = context.getLogger(Logger.ROOT_LOGGER_NAME)
            val file = File(dir, "$scope-${ProcessHandle.current().pid()}.log")
            val appender = RollingFileAppender<ILoggingEvent>().apply {
                this.context = context
                name = "DIAGNOSTICS"
                this.file = file.path
                encoder = PatternLayoutEncoder().apply {
                    this.context = context
                    pattern = "%date{ISO8601} %-5level [%thread] %logger - %msg%n"
                    start()
                }
                rollingPolicy = FixedWindowRollingPolicy().also { it.setParent(this) }.apply {
                    this.context = context
                    fileNamePattern = "${file.path}.%i.gz"
                    minIndex = 1
                    maxIndex = 2
                    start()
                }
                triggeringPolicy = SizeBasedTriggeringPolicy<ILoggingEvent>().apply {
                    this.context = context
                    maxFileSize = FileSize.valueOf("4MB")
                    start()
                }
                start()
            }
            root.addAppender(appender)
            privateFile(file)
            val crashes = File(home, "crashes").also(::privateDirectory)
            runCatching {
                ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean::class.java)
                    .setVMOption("ErrorFile", File(crashes, "hs_err_pid%p.log").absolutePath)
            }
            crashes.listFiles().orEmpty().filter { it.name.matches(Regex("(?:uncaught-.*|hs_err_pid[0-9]+)\\.log")) }
                .sortedByDescending { it.lastModified() }.forEachIndexed { index, old ->
                    if (index >= 12 || old.lastModified() < System.currentTimeMillis() - TimeUnit.DAYS.toMillis(7)) old.delete()
                }
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, error ->
                runCatching {
                    LoggerFactory.getLogger("kinuvo.crash").error("Uncaught exception on ${thread.name}", error)
                    val crash = File(home, "crashes/uncaught-${ProcessHandle.current().pid()}.log")
                    privateDirectory(crash.parentFile)
                    if (!crash.exists()) crash.createNewFile()
                    privateFile(crash)
                    crash.writeText(error.stackTraceToString().take(DiagnosticFiles.FILE_BYTES))
                }
                previous?.uncaughtException(thread, error) ?: error.printStackTrace()
            }
            val normal = root.level ?: Level.INFO
            val executor = Executors.newSingleThreadScheduledExecutor { runnable -> Thread(runnable, "diagnostic-recording").apply { isDaemon = true } }
            executor.scheduleAtFixedRate({ runCatching {
                root.level = if (status(home).recordingUntil > System.currentTimeMillis()) Level.DEBUG else normal
            } }, 0, 1, TimeUnit.SECONDS)
            // Bound retained files across process restarts, leaving live processes alone.
            dir.listFiles().orEmpty().filter { it.name.matches(Regex("(?:desktop|cli|server)-[0-9]+\\.log(?:\\.[12]\\.gz)?")) }
                .sortedByDescending { it.lastModified() }.forEachIndexed { index, old ->
                    val pid = old.name.substringAfter('-').substringBefore('.').toLongOrNull()
                    if (pid != null && !ProcessHandle.of(pid).map { it.isAlive }.orElse(false) &&
                        (index >= 24 || old.lastModified() < System.currentTimeMillis() - TimeUnit.DAYS.toMillis(7))) old.delete()
                }
        }.onFailure { System.err.println("Couldn't enable diagnostic logging: ${it.javaClass.simpleName}") }
    }

    fun status(home: File = diagnosticHome()): DiagnosticStatus {
        val until = runCatching { File(home, "diagnostic-recording").readText().trim().toLong() }.getOrDefault(0)
        return DiagnosticStatus(true, until.takeIf { it > System.currentTimeMillis() && it <= System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(30) } ?: 0)
    }

    fun record(minutes: Int, home: File = diagnosticHome()): DiagnosticStatus {
        require(minutes in 0..30) { "Recording must be between 0 and 30 minutes" }
        privateDirectory(home)
        val marker = File(home, "diagnostic-recording")
        if (minutes == 0) marker.delete() else {
            if (!marker.exists()) marker.createNewFile()
            privateFile(marker)
            marker.writeText((System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(minutes.toLong())).toString())
        }
        return status(home)
    }
}
