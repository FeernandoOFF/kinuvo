package kinuvo.diagnostics

import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LogBundleTest {
    private fun entries(zip: Path): Map<String, String> = ZipFile(File(zip.toString())).use { z ->
        z.entries().asSequence().associate { it.name to z.getInputStream(it).readBytes().decodeToString() }
    }

    private fun bundle(dir: Path, crashes: CrashReports = CrashReports(Path(dir, "crashes"), testApp)) =
        LogBundle(Path(dir, "logs"), crashes, testApp).write(Path(dir, "out"))

    @Test fun containsOnlyLogsCrashesAndSystemInfo() = withTemporaryDirectory { dir ->
        SystemFileSystem.createDirectories(Path(dir, "logs"))
        Path(dir, "logs/desktop.log").write("2026-11-24T10:00:00Z INFO  kinuvo.server - run.finish duration=830ms")
        Path(dir, "logs/desktop.1.log").write("older")
        Path(dir, "logs/notes.txt").write("not a log")
        Path(dir, "config.json").write("""{"apiKey":"nope"}""")
        Path(dir, "chat.jsonl").write("hello private message")
        CrashReports(Path(dir, "crashes"), testApp).record("main", IllegalStateException("boom"), at = 9)
        val zip = bundle(dir)
        val files = entries(zip)
        assertEquals(setOf("README.txt", "system.json", "logs/desktop.log", "logs/desktop.1.log", "crashes/crash-9.txt"), files.keys)
        assertContains(files.getValue("logs/desktop.log"), "run.finish duration=830ms")
        assertContains(files.getValue("crashes/crash-9.txt"), "IllegalStateException")
        val system = Json.parseToJsonElement(files.getValue("system.json")).jsonObject
        val app = system.getValue("app").jsonObject
        assertEquals("1.2.3", app.getValue("version").jsonPrimitive.content)
        assertEquals("45", app.getValue("build").jsonPrimitive.content)
        assertEquals("Android", app.getValue("platform").jsonPrimitive.content)
        assertEquals("Android 15", app.getValue("os").jsonPrimitive.content)
        assertFalse(files.values.any { "private message" in it || "nope" in it || "not a log" in it })
    }

    @Test fun redactsWhatAnEarlierBuildWroteUnredacted() = withTemporaryDirectory { dir ->
        SystemFileSystem.createDirectories(Path(dir, "logs"))
        Path(dir, "logs/server.log").write("Authorization: Bearer LEAKED_TOKEN\nsign-in failed me@example.test https://x.test/cb?code=LEAKED_CODE")
        val text = entries(bundle(dir)).getValue("logs/server.log")
        listOf("LEAKED_TOKEN", "me@example.test", "LEAKED_CODE").forEach { assertFalse(it in text, "$it in $text") }
    }

    @Test fun keepsOnlyTheNewestBytesOfLargeFilesAndBoundsTheTotal() = withTemporaryDirectory { dir ->
        SystemFileSystem.createDirectories(Path(dir, "logs"))
        val line = "x".repeat(99) + "\n"
        repeat(10) { Path(dir, "logs/${if (it == 0) "big" else "big.$it"}.log").write(line.repeat(15_000) + "END-$it\n") }
        val files = entries(bundle(dir))
        val logs = files.filterKeys { it.startsWith("logs/") }
        assertTrue(logs.values.sumOf { it.length } <= 6 * 1024 * 1024 + 1024)
        assertTrue(logs.size < 10)
        assertContains(logs.getValue("logs/big.log"), "END-0")
        assertTrue(logs.getValue("logs/big.log").startsWith("[Earlier content omitted]"))
        assertTrue(logs.getValue("logs/big.log").length < 1024 * 1024 + 100)
    }

    @Test fun exportsAreCappedAndTheArchiveIsValidWithoutLogs() = withTemporaryDirectory { dir ->
        var last: Path? = null
        repeat(5) { index ->
            last = LogBundle(Path(dir, "logs"), CrashReports(Path(dir, "crashes")), testApp).write(Path(dir, "out"), TickingClock(index))
        }
        assertEquals(3, SystemFileSystem.list(Path(dir, "out")).size)
        assertEquals(setOf("README.txt", "system.json"), entries(last!!).keys)
    }
}
