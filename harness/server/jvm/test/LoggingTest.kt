package kinuvo.harness.server

import ch.qos.logback.classic.LoggerContext
import io.github.oshai.kotlinlogging.KotlinLogging
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LoggingTest {
    @Test fun diagnosticsGoToStderrWithLevelsAndExceptions() {
        val output = probe()
        assertEquals("result:false\n", output.first)
        assertContains(output.second, "INFO")
        assertContains(output.second, "kinuvo.probe - ready")
        assertContains(output.second, "ERROR")
        assertContains(output.second, "IllegalStateException: probe failure")
        assertFalse("debug-detail" in output.second)
        assertFalse("No SLF4J providers" in output.second)
        assertTrue(Regex("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}").containsMatchIn(output.second))
    }

    @Test fun logLevelCanBeChangedWithoutChangingStdoutRouting() {
        val output = probe(level = "DEBUG")
        assertEquals("result:true\n", output.first)
        assertContains(output.second, "DEBUG")
        assertContains(output.second, "debug-detail")
    }

    @Test fun serviceLogsRotateWithoutWritingDiagnosticsToTheConsole() {
        val home = Files.createTempDirectory("kinuvo-logging").toFile()
        try {
            val file = File(home, "server.log")
            val output = probe(listOf("-Dlogback.configurationFile=logback-service.xml", "-Dkinuvo.log.file=${file.path}", "-Dkinuvo.log.maxFileSize=1KB"), rotate = true)
            assertEquals("result:false\n", output.first)
            assertEquals("", output.second)
            assertTrue(file.isFile)
            val archives = home.listFiles()!!.filter { it.name.endsWith(".gz") }
            assertTrue(archives.isNotEmpty(), "The configured rolling policy must create an archive")
            val archived = archives.joinToString { GZIPInputStream(it.inputStream()).bufferedReader().use { it.readText() } }
            assertContains(archived, "kinuvo.probe")
            assertContains(file.readText(), "last-entry")
        } finally { home.deleteRecursively() }
    }

    private fun probe(options: List<String> = emptyList(), level: String = "INFO", rotate: Boolean = false): Pair<String, String> {
        val java = File(System.getProperty("java.home"), "bin/java").path
        val process = ProcessBuilder(listOf(java) + options + listOf("-cp", System.getProperty("java.class.path"), LoggingProbe::class.java.name, rotate.toString()))
            .apply { environment()["KINUVO_LOG_LEVEL"] = level }.start()
        try {
            assertTrue(process.waitFor(20, TimeUnit.SECONDS), "Logging probe timed out")
            val stdout = process.inputStream.bufferedReader().use { it.readText() }
            val stderr = process.errorStream.bufferedReader().use { it.readText() }
            assertEquals(0, process.exitValue(), stderr)
            return stdout to stderr
        } finally { process.destroyForcibly() }
    }
}

object LoggingProbe {
    @JvmStatic fun main(args: Array<String>) {
        System.setProperty("kotlin-logging.logStartupMessage", "false")
        val log = KotlinLogging.logger("kinuvo.probe")
        var debugBuilt = false
        log.debug { debugBuilt = true; "debug-detail" }
        log.info { "ready" }
        log.error(IllegalStateException("probe failure")) { "operation failed" }
        if (args.single().toBoolean()) {
            repeat(200) { index -> log.info { "entry $index: ${"x".repeat(100)}" } }
            log.info { "last-entry" }
        }
        println("result:$debugBuilt")
        (LoggerFactory.getILoggerFactory() as LoggerContext).stop()
    }
}
