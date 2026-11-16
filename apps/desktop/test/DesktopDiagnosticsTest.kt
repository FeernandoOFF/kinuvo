package kinuvo.desktop

import kinuvo.app.diagnostics.LocalDiagnostics
import kinuvo.server.jvm.JvmDiagnostics
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopDiagnosticsTest {
    @Test fun clientInterfaceExportsFromTheAssignedHome() = runBlocking {
        val home = Files.createTempDirectory("kinuvo-desktop-diagnostics").toFile()
        try {
            File(home, "logs").mkdir()
            File(home, "logs/desktop-42.log").writeText("Example failure\napi_key=PRIVATE_VALUE")
            val diagnostics: LocalDiagnostics = DesktopDiagnostics(JvmDiagnostics(home, "this-device"))
            assertTrue(diagnostics.status().available)
            assertTrue(diagnostics.record(15).recordingUntil > System.currentTimeMillis())
            assertEquals(0, diagnostics.record(0).recordingUntil)
            val report = diagnostics.report()
            val destination = File(home, "diagnostics.zip")
            diagnostics.save(destination.path, mapOf("this-device" to report))
            ZipFile(destination).use { zip ->
                val system = zip.getInputStream(zip.getEntry("this-device/system.txt")).bufferedReader().use { it.readText() }
                val log = zip.getInputStream(zip.getEntry("this-device/logs/desktop-42.log")).bufferedReader().use { it.readText() }
                assertContains(system, "Scope: this-device")
                assertContains(log, "Example failure")
                assertFalse("PRIVATE_VALUE" in log)
            }
        } finally { home.deleteRecursively() }
    }
}
