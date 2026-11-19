package dev.kinuvo.android.embedded

import kinuvo.harness.acp.AcpLauncher
import kinuvo.harness.acp.NULL_DEVICE
import kinuvo.plugins.exec.ProotLinux
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun prootAcpLauncher(linux: ProotLinux) = AcpLauncher { command, cwd, environment ->
    linux.ensure()
    val guestCwd = cwd ?: "/root"
    withContext(Dispatchers.IO) {
        linux.guest(guestCwd).mkdirs()
        val builder = ProcessBuilder(linux.command(command, guestCwd, environment = environment)).redirectError(ProcessBuilder.Redirect.to(NULL_DEVICE))
        builder.environment().putAll(linux.environment())
        builder.start()
    }
}
