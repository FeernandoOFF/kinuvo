package kinuvo.harness.exec

import kinuvo.harness.core.Agent
import kinuvo.harness.core.AgentConfig
import kinuvo.harness.core.store.AgentFiles
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExecCheckTest {
    private val files = AgentFiles(Path(Files.createTempDirectory("harness-test").toFile().path))
    private val check = assertNotNull(ExecPlugin(files).execCheck)
    private val root = Files.createTempDirectory("ws").toFile().canonicalFile

    @Test
    fun listsTheWorkspaceAndRunsTheCommand() = runTest {
        File(root, "notes.md").writeText("hi")
        File(root, "src").mkdirs()
        val r = check.check(Agent("main", "Kinuvo", AgentConfig(exec = "host", workspace = listOf(root.path))), "echo ok")
        assertNull(r.error)
        assertEquals(listOf("notes.md", "src/"), r.files)
        assertEquals("ok", r.output?.trim())
        assertEquals(0, r.exitCode)
    }

    @Test
    fun reportsSettingsThatCannotWork() = runTest {
        val missing = check.check(Agent("main", "Kinuvo", AgentConfig(exec = "host", workspace = listOf("$root/nope"))), null)
        assertTrue("does not exist" in missing.error.orEmpty())
        assertTrue("ssh" in check.check(Agent("main", "Kinuvo", AgentConfig(exec = "ssh", workspace = listOf("/tmp"))), null).error.orEmpty())
    }
}
