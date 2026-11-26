package kinuvo.diagnostics

import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RotatingLogTest {
    @Test fun rotatesAtTheSizeLimitAndKeepsOnlyTheNewestFiles() = withTemporaryDirectory { dir ->
        val log = RotatingLog(dir, "desktop", maxBytes = 200, keep = 3)
        repeat(30) { log.append("line ${it.toString().padStart(2, '0')} " + "x".repeat(40)) }
        log.close()
        val files = log.files()
        assertEquals(listOf("desktop.log", "desktop.1.log", "desktop.2.log"), files.map { it.name })
        assertFalse(SystemFileSystem.exists(Path(dir, "desktop.3.log")))
        files.forEach { assertTrue(SystemFileSystem.metadataOrNull(it)!!.size <= 200, "${it.name} over the limit") }
        assertTrue("line 29" in files.first().read())
        assertFalse("line 00" in files.joinToString { it.read() })
    }

    @Test fun appendsToTheExistingFileAfterARestart() = withTemporaryDirectory { dir ->
        RotatingLog(dir, "cli").apply { append("first"); close() }
        RotatingLog(dir, "cli").apply { append("second"); close() }
        assertEquals("first\nsecond\n", Path(dir, "cli.log").read())
    }

    @Test fun neverThrowsWhenTheDirectoryIsUnusable() = withTemporaryDirectory { dir ->
        val blocked = Path(dir, "file").also { it.write("not a directory") }
        RotatingLog(Path(blocked, "logs"), "desktop").append("dropped")
    }
}
