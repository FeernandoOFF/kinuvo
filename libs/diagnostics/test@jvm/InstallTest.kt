package kinuvo.diagnostics

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class InstallTest {
    @Test fun aProcessWritesRedactedLogsAndLeavesACrashFileForTheNextLaunch() = withTemporaryDirectory { dir ->
        val output = File(dir.toString(), "output.txt")
        val process = ProcessBuilder(File(System.getProperty("java.home"), "bin/java").path, "-cp", System.getProperty("java.class.path"), InstallProbe::class.java.name, dir.toString())
            .redirectErrorStream(true).redirectOutput(output).start()
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Probe timed out")
            assertEquals(1, process.exitValue(), output.readText())
            val logs = Path(dir, "logs/probe.log").read()
            assertContains(logs, "launch version=1.2.3 build=45 platform=Android os=Android 15")
            assertContains(logs, "run.finish session=s1 duration=830ms")
            assertContains(logs, "IllegalStateException: failed")
            assertFalse("SECRET_VALUE" in logs || "me@example.test" in logs || "code=QUERY" in logs, logs)
            val diagnostics = Diagnostics(dir, testApp)
            val crash = assertNotNull(diagnostics.pendingCrash())
            assertContains(crash.summary, "IllegalStateException")
            assertContains(Path(dir, "logs/probe.log").read(), "Uncaught IllegalStateException on main")
            diagnostics.acknowledgeCrash()
            assertEquals(null, diagnostics.pendingCrash())
            val export = diagnostics.export(Path(dir, "out"))
            assertTrue(File(export.toString()).length() > 0)
        } finally { process.destroyForcibly() }
    }

    @Test fun aFailingWorkerThreadIsLoggedButIsNotACrash() = withTemporaryDirectory { dir ->
        val process = ProcessBuilder(File(System.getProperty("java.home"), "bin/java").path, "-cp", System.getProperty("java.class.path"), InstallProbe::class.java.name, dir.toString(), "worker")
            .redirectErrorStream(true).redirectOutput(File(dir.toString(), "output.txt")).start()
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Probe timed out")
            assertEquals(0, process.exitValue(), File(dir.toString(), "output.txt").readText())
            assertContains(Path(dir, "logs/probe.log").read(), "Uncaught IllegalStateException on probe-worker")
            assertEquals(null, Diagnostics(dir, testApp).pendingCrash())
            assertTrue(!SystemFileSystem.exists(Path(dir, "crashes")) || SystemFileSystem.list(Path(dir, "crashes")).isEmpty())
        } finally { process.destroyForcibly() }
    }
}

object InstallProbe {
    @JvmStatic fun main(args: Array<String>) {
        System.setProperty("kotlin-logging.logStartupMessage", "false")
        Diagnostics(Path(args.first()), testApp).install("probe")
        val log = KotlinLogging.logger("kinuvo.probe")
        log.info { "run.finish session=s1 duration=830ms" }
        log.warn(IllegalStateException("failed")) { "operation failed api_key=SECRET_VALUE for me@example.test https://x.test/cb?code=QUERY" }
        if (args.getOrNull(1) == "worker") {
            Thread({ throw IllegalStateException("worker failed") }, "probe-worker").apply { start(); join() }
            return
        }
        throw IllegalStateException("probe crash")
    }
}
