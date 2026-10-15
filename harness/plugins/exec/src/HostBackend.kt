package kinuvo.harness.exec

import java.io.File
import kotlin.time.Duration

/** Runs commands directly on this machine with the user's login environment. */
class HostBackend(workspace: List<File>) : ExecBackend(workspace.map { it.canonicalPath }) {
    override val label = "this machine (host)"

    override suspend fun execScript(script: String, cwd: String, stdin: String?, timeout: Duration, maxChars: Int): ExecResult =
        runProcess(listOf("bash", "-lc", script), File(cwd), stdin, timeout, maxChars = maxChars)
}
