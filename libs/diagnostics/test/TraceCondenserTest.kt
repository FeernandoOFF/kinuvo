package kinuvo.diagnostics

import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TraceCondenserTest {
    private fun deep(depth: Int, cause: Throwable): Throwable = if (depth == 0) IllegalStateException("outer failure", cause) else deep(depth - 1, cause)

    private fun nested(levels: Int): Throwable {
        var error: Throwable = IllegalArgumentException("root cause")
        for (level in 1..levels) error = deep(150, RuntimeException("level $level", error))
        return error
    }

    @Test fun everyCauseKeepsItsHeaderWhateverTheStackDepth() {
        val trace = condenseTrace(nested(6).stackTraceToString(), linesPerCause = 5, maxCauses = 12)
        assertContains(trace, "IllegalStateException: outer failure")
        (1..6).forEach { assertContains(trace, "level $it") }
        assertContains(trace, "IllegalArgumentException: root cause")
        assertTrue(trace.lineSequence().count() < 100, trace)
    }

    @Test fun crashFilesKeepTheCauseChain() = withTemporaryDirectory { dir ->
        CrashReports(dir, testApp).record("main", nested(5), at = 9)
        val text = Path(dir, "crash-9.txt").read()
        (1..5).forEach { assertContains(text, "level $it") }
        assertContains(text, "IllegalArgumentException: root cause")
    }

    @Test fun shortTracesAreUntouched() {
        val trace = "java.lang.IllegalStateException: a\n\tat x\nCaused by: java.lang.RuntimeException: b\n\tat y"
        assertEquals(trace, condenseTrace(trace, linesPerCause = 5, maxCauses = 3))
    }

    @Test fun causesBeyondTheLimitAreCounted() {
        val trace = (0..5).joinToString("\n") { (if (it == 0) "" else "Caused by: ") + "E$it\n\tat f$it" }
        val out = condenseTrace(trace, linesPerCause = 2, maxCauses = 2)
        assertContains(out, "E2")
        assertFalse("E3" in out)
        assertContains(out, "3 more causes")
    }
}
