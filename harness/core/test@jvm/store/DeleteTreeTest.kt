package kinuvo.harness.core.store

import kinuvo.harness.core.TestHome
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals

class DeleteTreeTest {
    @Test
    fun deletingAnAgentKeepsWhatItsSymlinksPointTo() = runTest {
        val home = TestHome()
        val agent = home.store.createAgent("work")
        val shared = Files.createTempDirectory("shared-skill").also { Files.writeString(it.resolve("SKILL.md"), "keep") }
        home.files.skills("work").createDirectories()
        Files.createSymbolicLink(Paths.get(Path(home.files.skills("work"), "linked").toString()), shared)
        home.store.deleteAgent(agent)
        assertEquals(false, home.files.agent("work").exists())
        assertEquals("keep", Files.readString(shared.resolve("SKILL.md")))
    }

    @Test
    fun privateWritesAreOwnerOnly() {
        val file = Path(TestHome().files.home, "secret.json")
        writeAtomically(file, "{}", private = true)
        assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(Paths.get(file.toString()))))
    }
}
