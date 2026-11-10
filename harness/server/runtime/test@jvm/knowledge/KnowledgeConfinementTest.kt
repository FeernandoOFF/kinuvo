package kinuvo.harness.core.knowledge

import kinuvo.harness.core.TestHome
import kinuvo.harness.core.store.FileKnowledgeChangeLog
import kinuvo.harness.core.store.MarkdownKnowledgeSource
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertNull

class KnowledgeConfinementTest {
    @Test
    fun symlinkedFoldersCantLeadOutOfTheVault() = runTest {
        val home = TestHome()
        val root = Path(home.files.home, "vault")
        val outside = Files.createTempDirectory("outside")
        Files.createDirectories(Paths.get(root.toString()))
        Files.createSymbolicLink(Paths.get(root.toString(), "Projects"), outside)
        val source = MarkdownKnowledgeSource(root)
        val knowledge = Knowledge({ source }, FileKnowledgeChangeLog(home.files.knowledgeChanges))
        assertFailsWith<IllegalArgumentException> { knowledge.create("project", "Outside") }
        assertFalse(Files.exists(outside.resolve("Outside.md")))
    }

    @Test
    fun searchesIgnoreEscapingFilesAndSymlinkCycles() = runTest {
        val root = Files.createTempDirectory("vault")
        val outside = Files.createTempFile("outside", ".md")
        Files.writeString(outside, "Private text")
        Files.createSymbolicLink(root.resolve("Private.md"), outside)
        Files.createSymbolicLink(root.resolve("Cycle"), root)
        val source = MarkdownKnowledgeSource(Path(root.toString()))
        assertTrue(source.search("").isEmpty())
        assertNull(source.read("Private"))
        assertFalse("Private" in source.schema().types.map { it.key })
    }
}
