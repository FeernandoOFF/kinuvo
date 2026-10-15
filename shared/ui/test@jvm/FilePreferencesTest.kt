package kinuvo.ui

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FilePreferencesTest {
    @Test
    fun keepsOneFilePerKey() {
        val dir = File(Files.createTempDirectory("desktop").toFile(), "nested")
        val prefs = FilePreferences(dir)
        assertNull(prefs["view"])
        prefs["view"] = "{}"
        assertEquals("{}", FilePreferences(dir)["view"])
        assertEquals("{}", File(dir, "desktop-view.json").readText())
        prefs["view"] = null
        assertNull(prefs["view"])
    }
}
