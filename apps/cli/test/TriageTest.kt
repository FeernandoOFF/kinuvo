package kinuvo.cli

import com.github.ajalt.clikt.command.parse
import com.github.ajalt.clikt.core.context
import kinuvo.testing.harness.TemporaryDirectories
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith

class TriageTest {
    private val directories = TemporaryDirectories()
    @AfterTest fun close() = directories.close()

    @Test fun analysisCreatesDraftWithoutPublishingAndPublishUsesReviewedText() = runBlocking {
        val root = directories.create("triage-cli").toFile()
        val archive = File(root, "diagnostics.zip")
        ZipOutputStream(archive.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("this-device/logs/desktop.log"))
            zip.write("Connection refused\napi_key=TOPSECRET".toByteArray())
            zip.closeEntry()
        }
        var requests = 0
        var publications = 0
        val ai = object : TriageAi {
            override suspend fun analyze(provider: String, model: String, context: String): String {
                requests++
                assertContains(context, "Connection refused")
                assertFalse("TOPSECRET" in context)
                return "# Connection fails\n\nObserved: connection refused. Cause unknown."
            }
        }
        val publish: (String, String, File) -> String = { repo, title, body ->
            publications++
            assertEquals("example/kinuvo", repo)
            assertEquals("Reviewed title", title)
            assertContains(body.readText(), "Edited after review")
            "https://github.com/example/kinuvo/issues/1"
        }
        val output = StringBuilder()
        suspend fun run(vararg args: String) = TriageCommand(ai, publish).context { echoMessage = { _, text, _, _ -> output.append(text).append('\n') } }.parse(args.toList())
        val destination = File(root, "triage")
        run("The app cannot connect", "--report", archive.path, "--output", destination.path, "--yes")
        assertEquals(1, requests)
        assertEquals(0, publications)
        val draft = File(destination, "issue.md")
        assertContains(draft.readText(), "Cause unknown")
        draft.writeText("# Reviewed title\n\nEdited after review")
        run("--publish", draft.path, "--repo", "example/kinuvo", "--yes")
        assertEquals(1, requests)
        assertEquals(1, publications)
    }

    @Test fun rejectsInvalidDraftsAndArchivePaths() {
        assertFailsWith<IllegalArgumentException> { issueTitle("No title") }
        val archive = directories.create("triage-zip").toFile().resolve("bad.zip")
        ZipOutputStream(archive.outputStream()).use {
            it.putNextEntry(ZipEntry("../private.log")); it.write("secret".toByteArray()); it.closeEntry()
        }
        assertFailsWith<IllegalArgumentException> { triageContext("problem", archive) }
    }
}
