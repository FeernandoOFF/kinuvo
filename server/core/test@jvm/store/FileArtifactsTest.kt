package kinuvo.server.store

import kinuvo.protocol.agent.AgentId
import kinuvo.server.TestHome
import kinuvo.server.artifacts.Artifacts
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FileArtifactsTest {
    private val home = TestHome()
    private val artifacts = Artifacts(FileArtifacts(home.files.artifacts))
    private val root = Path(home.files.artifacts.toString()).toAbsolutePath()

    @Test
    fun linksOutOfAFolderAreNeitherListedReadNorWrittenThrough() = runTest {
        val id = artifacts.createBundle(AgentId("main"), null, "App", mapOf("index.html" to "<p/>".encodeToByteArray())).id
        val outside = root.resolveSibling("outside-${id}").also { it.createDirectories() }
        outside.resolve("secret.txt").writeText("secret")
        Files.createSymbolicLink(root.resolve(id).resolve("secret.txt"), outside.resolve("secret.txt"))
        Files.createSymbolicLink(root.resolve(id).resolve("out"), outside)

        assertEquals(listOf("index.html"), artifacts.files(id).files.map { it.path })
        assertFailsWith<IllegalStateException> { artifacts.readFile(id, "secret.txt") }
        assertFailsWith<IllegalStateException> { artifacts.readFile(id, "out/secret.txt") }
        assertFailsWith<IllegalArgumentException> { artifacts.writeFiles(id, AgentId("main"), null, mapOf("out/new.txt" to byteArrayOf(1))) }
        assertTrue(Files.notExists(outside.resolve("new.txt")))

        artifacts.delete(id)
        assertEquals("secret", outside.resolve("secret.txt").toFile().readText())
    }

    @Test
    fun aFolderAndTheOldLayoutListTogether() = runTest {
        val single = artifacts.create(AgentId("main"), null, "Old", "html", "<p>old</p>")
        val bundle = artifacts.createBundle(AgentId("main"), null, "New", mapOf("index.html" to "<p>new</p>".encodeToByteArray())).id
        assertEquals(setOf(single.id, bundle), artifacts.list().map { it.id }.toSet())
        assertEquals("<p>old</p>", artifacts.get(single.id).content)
        assertEquals("<p>new</p>", artifacts.get(bundle).content)
    }
}
