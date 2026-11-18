package kinuvo.plugins.cua

import kinuvo.protocol.device.Platform
import kinuvo.protocol.device.currentPlatform
import java.io.File

internal fun keepAwakeCommand(platform: Platform, available: (String) -> Boolean = { name ->
    System.getenv("PATH").orEmpty().split(File.pathSeparator).any { File(it, name).canExecute() }
}): List<String>? = when (platform) {
    Platform.MacOS -> listOf("/usr/bin/caffeinate", "-diu", "-t", "15")
    Platform.Windows -> listOf("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", """
        Add-Type -TypeDefinition 'using System; using System.Runtime.InteropServices; public class Awake { [DllImport("kernel32.dll")] public static extern uint SetThreadExecutionState(uint flags); }';
        if ([Awake]::SetThreadExecutionState( [uint32]2147483651 ) -eq 0) { exit 1 }; Start-Sleep -Seconds 15
    """.trimIndent())
    Platform.Linux -> if (available("systemd-inhibit")) listOf("systemd-inhibit", "--what=idle:sleep", "--why=Kinuvo Computer use", "sleep", "15") else null
    else -> null
}

internal class KeepAwake(
    private val platform: Platform = currentPlatform,
    private val available: (String) -> Boolean = { name -> System.getenv("PATH").orEmpty().split(File.pathSeparator).any { File(it, name).canExecute() } },
    private val start: (List<String>) -> Process = { command -> ProcessBuilder(command)
        .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start() },
    private val now: () -> Long = System::nanoTime,
) {
    private var process: Process? = null
    private var startedAt = 0L

    fun update(active: Boolean) {
        if (!active) { process?.destroy(); process = null; return }
        val time = now()
        if (process?.isAlive == true && time - startedAt < 5_000_000_000L) return
        val command = keepAwakeCommand(platform, available) ?: return
        val next = runCatching { start(command) }.getOrNull() ?: return
        process?.destroy()
        process = next
        startedAt = time
    }
}
