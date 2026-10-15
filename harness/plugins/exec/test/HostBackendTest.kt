package kinuvo.harness.exec

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class HostBackendTest {
    private val root = Files.createTempDirectory("ws").toFile().canonicalFile
    private val backend = HostBackend(listOf(root))
    private val tools = execTools(backend).associateBy { it.descriptor.name }
    private suspend fun call(tool: String, vararg pairs: Pair<String, Any>) = tools.getValue(tool).execute(buildJsonObject {
        pairs.forEach { (k, v) -> if (v is Int) put(k, v) else put(k, v.toString()) }
    })

    @Test
    fun shellRunsInWorkspaceAndReportsExitCode() = runTest {
        File(root, "sub").mkdirs()
        assertEquals("[exit code 0]\n${root.path}/sub", call("shell", "command" to "pwd", "cwd" to "sub"))
        assertTrue(call("shell", "command" to "echo oops >&2; exit 3").startsWith("[exit code 3]\noops"))
    }

    @Test
    fun shellTimesOutAndTruncates() = runTest {
        val r = backend.run("sleep 5", null, 1.seconds)
        assertTrue(r.timedOut)
        val long = backend.run("seq 1 100000", null, 10.seconds).output
        assertTrue(long.length < 32_000 && long.startsWith("1\n") && long.endsWith("100000") && "lines omitted" in long)
    }

    @Test
    fun wholeReadsKeepLongFiles() = runTest {
        val page = "<html>\n" + (1..5_000).joinToString("\n") { "<p>line $it</p>" } + "\n</html>"
        File(root, "report.html").writeText(page)
        assertEquals(page, backend.read("report.html", 5_000_000, whole = true))
        assertTrue("omitted" in backend.read("report.html"))
        assertTrue("larger" in assertFailsWith<IllegalStateException> { backend.read("report.html", 1_000, whole = true) }.message!!)
        Files.createSymbolicLink(File(root, "hosts").toPath(), File("/etc/hosts").toPath())
        assertTrue("outside" in assertFailsWith<IllegalStateException> { backend.read("hosts") }.message!!)
        File(root, "inside").let { Files.createSymbolicLink(it.toPath(), File(root, "report.html").toPath()) }
        assertEquals(page, backend.read("inside", 5_000_000, whole = true))
    }

    @Test
    fun fileToolsRoundTripAndStayInWorkspace() = runTest {
        call("write_file", "path" to "a/b.txt", "content" to "one\ntwo 'quoted'\nthree")
        assertEquals("2\ttwo 'quoted'", call("read_file", "path" to "a/b.txt", "offset" to 2, "limit" to 1).lines().first())
        call("edit_file", "path" to "a/b.txt", "old" to "two", "new" to "TWO")
        assertEquals("one\nTWO 'quoted'\nthree", File(root, "a/b.txt").readText())
        assertFailsWith<IllegalArgumentException> { call("edit_file", "path" to "a/b.txt", "old" to "missing", "new" to "x") }
        assertEquals("a/b.txt", call("list_files", "glob" to "**/*.txt"))
        assertFailsWith<IllegalArgumentException> { call("read_file", "path" to "../etc/passwd") }
        assertFailsWith<IllegalArgumentException> { call("write_file", "path" to "/tmp/outside.txt", "content" to "x") }
    }
}
