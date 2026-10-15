package kinuvo.harness.exec

import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class SshBackendTest {
    private val control = Files.createTempDirectory("ssh").toFile()

    @Test
    fun buildsSshInvocationWithSharedConnection() {
        val b = SshBackend("box", "me", 2222, null, listOf("/srv/app"), mapOf("A" to "it's"), control)
        val argv = b.argv(b.remoteCommand("echo hi", "/srv/app"))
        assertEquals(listOf("ssh", "-o", "BatchMode=yes", "-o", "ControlMaster=auto", "-o", "ControlPersist=10m", "-o", "ControlPath=${File(control, "%C").path}", "-p", "2222", "me@box"), argv.dropLast(1))
        val check = SshBackend("box", null, null, null, listOf("/srv"), emptyMap(), null).argv("true")
        assertEquals(listOf("ssh", "-o", "BatchMode=yes", "-o", "ControlMaster=no", "-o", "ControlPath=none", "box", "true"), check)
        assertTrue(argv.last().startsWith("cd '/srv/app' && bash -c ") && argv.last().endsWith(" 'echo hi'"))
        assertTrue("it's" !in argv.joinToString(" "))
    }

    @Test
    fun exportsArriveOnStdinAheadOfTheScriptsInput() {
        val b = SshBackend("box", null, null, null, listOf("/tmp"), mapOf("A" to "it's \"q\"\nline"), control)
        val p = ProcessBuilder("sh", "-c", b.remoteCommand("printf '%s|' \"\$A\"; cat", "/tmp")).start()
        p.outputStream.use { it.write(b.input("piped")!!.toByteArray()) }
        assertEquals("it's \"q\"\nline|piped", p.inputStream.bufferedReader().readText())
    }

    /** KINUVO_SSH_TARGET=user@host KINUVO_SSH_DIR=/abs/remote/dir to run against a real machine. */
    @Test
    fun runsOnRealHost() {
        val target = System.getenv("KINUVO_SSH_TARGET") ?: return
        val dir = System.getenv("KINUVO_SSH_DIR") ?: return
        val (user, host) = target.split("@").let { if (it.size == 2) it[0] to it[1] else null to it[0] }
        val b = SshBackend(host, user, null, null, listOf(dir), emptyMap(), control)
        runBlocking {
            b.write("harness-ssh-test.txt", "over ssh")
            assertEquals("over ssh", b.read("harness-ssh-test.txt"))
            assertEquals("[exit code 0]\n$dir", "[exit code ${b.run("pwd", null, 30.seconds).exitCode}]\n" + b.run("pwd", null, 30.seconds).output)
            b.run("rm -f harness-ssh-test.txt", null, 30.seconds)
            b.close()
        }
    }
}
