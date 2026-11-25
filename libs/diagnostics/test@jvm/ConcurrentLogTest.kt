package kinuvo.diagnostics

import kotlinx.io.files.Path
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConcurrentLogTest {
    @Test fun concurrentWritersKeepWholeLines() = withTemporaryDirectory { dir ->
        val log = RotatingLog(dir, "desktop", maxBytes = 1 shl 20)
        val workers = (1..4).map { id -> thread { repeat(200) { log.append("worker $id line $it") } } }
        workers.forEach { it.join() }
        log.close()
        val lines = Path(dir, "desktop.log").read().lines().filter { it.isNotEmpty() }
        assertEquals(800, lines.size)
        assertTrue(lines.all { Regex("worker \\d line \\d+").matches(it) })
    }
}
