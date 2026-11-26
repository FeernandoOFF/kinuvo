package kinuvo.diagnostics

import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class CrashReportsTest {
    @Test fun aCrashIsOfferedOnceUntilTheUserHasBeenAsked() = withTemporaryDirectory { dir ->
        val crashes = CrashReports(dir, testApp)
        assertNull(crashes.pending())
        crashes.record("main", IllegalStateException("boom api_key=SECRET_VALUE"), at = 1000)
        val pending = assertNotNull(crashes.pending())
        assertEquals(1000, pending.at)
        assertContains(pending.summary, "IllegalStateException")
        crashes.acknowledge()
        assertNull(crashes.pending())
        crashes.record("worker-1", RuntimeException("again"), at = 2000)
        assertEquals(2000, crashes.pending()?.at)
    }

    @Test fun crashFilesNameTheAppAndThreadAndAreRedacted() = withTemporaryDirectory { dir ->
        val crashes = CrashReports(dir, testApp)
        crashes.record("main", IllegalStateException("failed for me@example.test with password=SECRET_VALUE at https://x.test/a?token=ABC"), at = 5)
        val text = Path(dir, "crash-5.txt").read()
        assertContains(text, "Thread: main")
        assertContains(text, "App: 1.2.3 (45) on Android, Android 15")
        assertContains(text, "IllegalStateException")
        listOf("me@example.test", "SECRET_VALUE", "token=ABC").forEach { assertFalse(it in text, "$it in $text") }
    }

    @Test fun onlyTheNewestFilesAreKept() = withTemporaryDirectory { dir ->
        val crashes = CrashReports(dir, testApp, keep = 3)
        (1..6).forEach { crashes.record("main", RuntimeException("n$it"), at = it * 100L) }
        crashes.acknowledge()
        assertEquals(listOf("crash-600.txt", "crash-500.txt", "crash-400.txt"), crashes.files().map { it.name })
    }

    @Test fun recordingNeverThrows() = withTemporaryDirectory { dir ->
        val blocked = Path(dir, "file").also { it.write("not a directory") }
        CrashReports(Path(blocked, "crashes")).record("main", RuntimeException("x"))
    }
}
