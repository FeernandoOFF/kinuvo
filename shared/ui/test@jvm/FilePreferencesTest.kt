package kinuvo.ui

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FilePreferencesTest {
    @Test
    fun keepsEveryKeyInOneFile() {
        val dir = File(Files.createTempDirectory("desktop").toFile(), "nested")
        val prefs = FilePreferences(dir)
        assertNull(prefs["view"])
        prefs["view"] = "{}"
        assertEquals("{}", FilePreferences(dir)["view"])
        prefs["current"] = "local"
        assertEquals(listOf("desktop.json"), dir.list()!!.toList())
        prefs["view"] = null
        assertNull(FilePreferences(dir)["view"])
        assertEquals("local", FilePreferences(dir)["current"])
    }
}
