package kinuvo.app

import kinuvo.app.settings.openSourceLicenses
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OpenSourceLicensesTest {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "project.yaml").isFile }

    private fun shipped(module: String): List<String> {
        var section: String? = null
        val coordinates = mutableListOf<String>()
        for (line in File(root, "$module/module.yaml").readLines()) {
            if (line.isNotEmpty() && !line[0].isWhitespace() && !line.startsWith("#")) section = line.substringBefore(':').trim()
            val entry = line.trim().removePrefix("- ").substringBefore(' ').trimEnd(':')
            if (section?.startsWith("dependencies") == true && line.trim().startsWith("- ") && entry.count { it == ':' } >= 2) coordinates += entry.substringBeforeLast(':').let { if (it.count { c -> c == ':' } >= 2) it.substringBeforeLast(':') else it }
        }
        return coordinates
    }

    @Test
    fun `every direct Maven dependency in module yaml has a license entry`() {
        val modules = File(root, "project.yaml").readLines().filter { it.startsWith("  - ") }.map { it.removePrefix("  - ").trim() }
        val prefixes = openSourceLicenses.flatMap { it.maven }
        val missing = modules.flatMap { m -> shipped(m).map { "$it (in $m)" to it } }.filter { (_, c) -> prefixes.none { c.startsWith(it) } }.map { it.first }.distinct()
        assertEquals(emptyList(), missing, "Add these to openSourceLicenses in client/app/src/settings/OpenSourceLicenses.kt")
    }

    @Test
    fun `every bundled native library and font has a license entry`() {
        val prefixes = openSourceLicenses.flatMap { it.files }
        val libs = File(root, "apps/android/jniLibs").listFiles { f -> f.isDirectory }.orEmpty().flatMap { it.listFiles().orEmpty().toList() }
        val bundled = (libs + File(root, "client/ui/composeResources/font").listFiles().orEmpty()).map { it.name }.distinct()
        val missing = bundled.filter { name -> prefixes.none { name.startsWith(it) } }
        assertEquals(emptyList(), missing, "Add these file prefixes to openSourceLicenses")
    }

    @Test
    fun `every license has its text`() {
        val texts = File(root, "client/ui/composeResources/files/licenses")
        val missing = openSourceLicenses.flatMap { it.texts.map { id -> id to it.name } }.filter { (id, _) -> !File(texts, "$id.txt").isFile && !id.startsWith("LicenseRef-") }
        assertTrue(missing.isEmpty(), "No text for $missing")
    }
}
