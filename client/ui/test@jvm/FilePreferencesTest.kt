package kinuvo.ui

import kotlin.test.AfterTest
import kinuvo.testing.harness.TemporaryDirectories
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FilePreferencesTest {
    private val temporaryDirectories = TemporaryDirectories()

    @AfterTest
    fun deleteTemporaryDirectories() = temporaryDirectories.close()

    @Test
    fun keepsEveryKeyInOneFile() {
        val dir = File(temporaryDirectories.create("desktop").toFile(), "nested")
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
