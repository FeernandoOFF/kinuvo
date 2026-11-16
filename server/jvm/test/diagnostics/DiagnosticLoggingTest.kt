package kinuvo.server.jvm.diagnostics

import org.slf4j.Logger
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiagnosticLoggingTest {
    @Test fun processCapturesLogsDebugRecordingAndUncaughtCrash() {
        val home = Files.createTempDirectory("kinuvo-logging-probe").toFile()
        try {
            val output = File(home, "output.txt")
            val process = ProcessBuilder(File(System.getProperty("java.home"), "bin/java").path,
                "-cp", System.getProperty("java.class.path"), DiagnosticLoggingProbe::class.java.name, home.path)
                .redirectErrorStream(true).redirectOutput(output).start()
            try {
                assertTrue(process.waitFor(20, TimeUnit.SECONDS), "Logging probe timed out")
                assertEquals(1, process.exitValue(), output.readText())
                val report = DiagnosticFiles(home).report("test").files
                val logs = report.filterKeys { it.startsWith("logs/") }.values.joinToString("\n")
                assertContains(logs, "ordinary log")
                assertContains(logs, "debug while recording")
                assertFalse("debug after stopping" in logs)
                val crash = report.filterKeys { it.startsWith("crashes/") }.values.single()
                assertContains(crash, "IllegalStateException")
                assertContains(crash, "probe crash")
            } finally { process.destroyForcibly() }
        } finally { home.deleteRecursively() }
    }
}

object DiagnosticLoggingProbe {
    @JvmStatic fun main(args: Array<String>) {
        val home = File(args.single())
        val context = LoggerFactory.getILoggerFactory() as LoggerContext
        context.getLogger(Logger.ROOT_LOGGER_NAME).level = Level.INFO
        DiagnosticLogging.install("desktop", home)
        val log = LoggerFactory.getLogger("kinuvo.probe")
        log.info("ordinary log")
        DiagnosticLogging.record(1, home)
        Thread.sleep(1500)
        log.debug("debug while recording")
        DiagnosticLogging.record(0, home)
        Thread.sleep(1500)
        log.debug("debug after stopping")
        throw IllegalStateException("probe crash")
    }
}
