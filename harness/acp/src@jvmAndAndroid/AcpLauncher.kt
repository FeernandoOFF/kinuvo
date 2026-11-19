package kinuvo.harness.acp

import kinuvo.protocol.device.Platform
import kinuvo.protocol.device.currentPlatform
import java.io.File

fun interface AcpLauncher {
    suspend fun start(command: List<String>, cwd: String?, environment: Map<String, String>): Process
}

// Redirect.DISCARD is unavailable on Android.
val NULL_DEVICE = File(if (currentPlatform == Platform.Windows) "NUL" else "/dev/null")

val DefaultAcpLauncher = AcpLauncher { command, cwd, environment ->
    ProcessBuilder(command).directory(cwd?.let(::File))
        .apply { environment().putAll(environment) }.redirectError(ProcessBuilder.Redirect.to(NULL_DEVICE)).start()
}
