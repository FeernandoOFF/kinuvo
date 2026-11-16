package kinuvo.server.jvm.diagnostics

import kinuvo.protocol.diagnostics.DiagnosticReport
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DiagnosticsTest {
    @Test fun redactsCredentialsAndLocalUserPaths() {
        val text = """
            Authorization: Bearer TOP_SECRET_VALUE
            Cookie: session=COOKIE_SECRET; other=OTHER_SECRET
            api_key="API_SECRET"
            {"access_token":"ACCESS_SECRET","password":"PASS_SECRET"}
            https://user:URL_SECRET@example.test/path?token=QUERY_SECRET&code=CODE_SECRET
            /test/user/Documents
            ghp_abcdefghijklmnopqrst
        """.trimIndent()
        val redacted = Redaction("/test/user").apply(text)
        listOf("TOP_SECRET_VALUE", "COOKIE_SECRET", "OTHER_SECRET", "API_SECRET", "ACCESS_SECRET", "PASS_SECRET", "URL_SECRET", "QUERY_SECRET", "CODE_SECRET", "ghp_abcdefghijklmnopqrst", "/test/user").forEach { assertFalse(it in redacted, "$it remains in $redacted") }
        assertContains(redacted, "<user-home>/Documents")
    }

    @Test fun redactsWindowsHomePathsInPlainAndJsonLogs() {
        val redact = Redaction("C:\\Users\\Alice")
        assertEquals("<user-home>\\data", redact.apply("c:\\users\\alice\\data"))
        assertEquals("<user-home>/data", redact.apply("C:/Users/Alice/data"))
        assertEquals("<user-home>\\\\data", redact.apply("C:\\\\Users\\\\Alice\\\\data"))
    }

    @Test fun exportsOnlyBoundedDiagnosticFilesAndNeverFollowsLinks() = temporary { home ->
        File(home, "logs").mkdir()
        File(home, "traces").mkdir()
        File(home, "logs/desktop-123.log").writeText("x".repeat(DiagnosticFiles.FILE_BYTES * 2) + "\npassword=private\nrecent error")
        File(home, "logs/database.json").writeText("do not export")
        File(home, "traces/spans-0.jsonl").writeText("trace metadata")
        File(home, "config.yaml").writeText("api_key: private")
        val outside = File(home, "private.txt").apply { writeText("never share this") }
        Files.createSymbolicLink(File(home, "logs/server.log").toPath(), outside.toPath())
        val files = DiagnosticFiles(home).report("test").files
        assertContains(files.keys, "logs/desktop-123.log")
        assertContains(files.keys, "traces/spans-0.jsonl")
        assertFalse("logs/server.log" in files)
        assertFalse("logs/database.json" in files)
        assertFalse("config.yaml" in files)
        assertTrue(files.getValue("logs/desktop-123.log").length < DiagnosticFiles.FILE_BYTES + 100)
        assertContains(files.getValue("logs/desktop-123.log"), "recent error")
        assertFalse("private" in files.getValue("logs/desktop-123.log"))
        val zip = File(home, "report.zip")
        DiagnosticFiles(home).writeZip(zip, mapOf("this-device" to DiagnosticReport(files)))
        ZipFile(zip).use { assertEquals(files.size, it.size()) }
        assertFailsWith<IllegalArgumentException> { DiagnosticFiles(home).writeZip(zip, emptyMap()) }
    }

    @Test fun nativeCrashExportsStackWithoutMemoryOrEnvironment() = temporary { home ->
        File(home, "crashes").mkdir()
        File(home, "crashes/hs_err_pid123.log").writeText("""
            # A fatal error has been detected by the Java Runtime Environment
            # SIGSEGV
            Current thread: JavaThread main
            Native frames:
            C  [native-library+0x123]
            Java frames:
            j  app.Main.main()
            siginfo: diagnostic data
            Registers:
            PRIVATE_MEMORY_CONTENT
            Environment Variables:
            PERSONAL_VARIABLE=private-value
        """.trimIndent())
        val crash = DiagnosticFiles(home).report("test").files.getValue("crashes/hs_err_pid123.log")
        assertContains(crash, "SIGSEGV")
        assertContains(crash, "app.Main.main")
        assertFalse("PRIVATE_MEMORY_CONTENT" in crash)
        assertFalse("PERSONAL_VARIABLE" in crash)
    }

    @Test fun debugRecordingIsBoundedAndCanBeStopped() = temporary { home ->
        assertEquals(0, DiagnosticLogging.status(home).recordingUntil)
        assertTrue(DiagnosticLogging.record(15, home).recordingUntil > System.currentTimeMillis())
        assertEquals(0, DiagnosticLogging.record(0, home).recordingUntil)
        assertFailsWith<IllegalArgumentException> { DiagnosticLogging.record(31, home) }
        File(home, "diagnostic-recording").writeText("1")
        assertEquals(0, DiagnosticLogging.status(home).recordingUntil)
    }

    private fun temporary(block: (File) -> Unit) {
        val dir = Files.createTempDirectory("kinuvo-diagnostics-test").toFile()
        try { block(dir) } finally { dir.deleteRecursively() }
    }
}
