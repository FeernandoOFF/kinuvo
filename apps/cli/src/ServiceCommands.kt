package kinuvo.cli

import com.github.ajalt.clikt.command.CoreSuspendingCliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import kinuvo.harness.server.HarnessServer
import kinuvo.harness.server.LocalServer
import kinuvo.harness.server.kinuvoHome
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions

internal class ServiceCommands : Group(
    "service", "Run the Server as a service of this machine's user, started at login and restarted when it exits (launchd or systemd).",
    InstallService(), UninstallService(), ServiceStatus(), RestartService(), ServiceLogs(),
)

/** What the service runs: [jar] with this JVM, serving on [host]:[port], logging to [log]. */
internal data class ServiceSpec(val java: String, val jar: String, val host: String, val port: Int, val log: String, val env: Map<String, String>, val computer: Boolean = false, val tailscale: Boolean = false, val tailscalePort: Int = 443) {
    val command get() = listOf(java, "-jar", jar, "serve", "--host", host, "--port", port.toString()) +
        (if (computer) listOf("--computer") else emptyList()) +
        (if (tailscale) listOf("--tailscale", "--tailscale-port", tailscalePort.toString()) else emptyList())
}

/** The user's launchd agent or systemd unit for the Server. */
internal interface ServiceManager {
    val file: File
    fun render(spec: ServiceSpec): String
    fun install(spec: ServiceSpec)
    fun uninstall()
    fun restart()
    /** E.g. `running (pid 123)`, `stopped`. */
    fun state(): String
    /** Something the user still has to do for the service to start at boot; null when nothing. */
    fun bootHint(): String? = null

    companion object {
        fun current(): ServiceManager {
            val os = System.getProperty("os.name").lowercase()
            return when {
                "mac" in os -> Launchd()
                "linux" in os -> Systemd()
                else -> throw CliktError("Services are supported on macOS (launchd) and Linux (systemd), not ${System.getProperty("os.name")}")
            }
        }
    }
}

internal class Launchd(private val home: File = File(System.getProperty("user.home"))) : ServiceManager {
    override val file = File(home, "Library/LaunchAgents/$LABEL.plist")
    private val target by lazy { "gui/${sh("id", "-u").out.trim()}" }

    override fun render(spec: ServiceSpec) = buildString {
        appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
        appendLine("""<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">""")
        appendLine("""<plist version="1.0">""")
        appendLine("<dict>")
        appendLine("  <key>Label</key><string>$LABEL</string>")
        appendLine("  <key>ProgramArguments</key>")
        appendLine("  <array>")
        spec.command.forEach { appendLine("    <string>${xml(it)}</string>") }
        appendLine("  </array>")
        appendLine("  <key>EnvironmentVariables</key>")
        appendLine("  <dict>")
        spec.env.forEach { (k, v) -> appendLine("    <key>${xml(k)}</key><string>${xml(v)}</string>") }
        appendLine("  </dict>")
        appendLine("  <key>RunAtLoad</key><true/>")
        appendLine("  <key>KeepAlive</key><true/>")
        appendLine("  <key>ThrottleInterval</key><integer>10</integer>")
        appendLine("  <key>StandardOutPath</key><string>${xml(spec.log)}</string>")
        appendLine("  <key>StandardErrorPath</key><string>${xml(spec.log)}</string>")
        appendLine("</dict>")
        appendLine("</plist>")
    }

    override fun install(spec: ServiceSpec) {
        file.parentFile.mkdirs()
        file.writeText(render(spec))
        bootout()
        // Bootstrap can still fail (error 5) just after the old Server goes, so try a few times.
        var result = sh("launchctl", "bootstrap", target, file.path)
        for (attempt in 1..4) {
            if (result.code == 0) break
            Thread.sleep(1_000)
            result = sh("launchctl", "bootstrap", target, file.path)
        }
        result.orFail()
    }

    /** Unloads the agent and waits until launchd has let it go; bootout returns before it has. */
    private fun bootout() {
        sh("launchctl", "bootout", "$target/$LABEL")
        repeat(20) {
            if (sh("launchctl", "print", "$target/$LABEL").code != 0) return
            Thread.sleep(500)
        }
    }

    override fun uninstall() {
        bootout()
        file.delete()
    }

    override fun restart() = sh("launchctl", "kickstart", "-k", "$target/$LABEL").orFail()

    override fun state(): String {
        val r = sh("launchctl", "print", "$target/$LABEL")
        if (r.code != 0) return "not loaded"
        val state = Regex("""\n\s*state = (\S+)""").find(r.out)?.groupValues?.get(1) ?: "unknown"
        return Regex("""\n\s*pid = (\d+)""").find(r.out)?.let { "$state (pid ${it.groupValues[1]})" } ?: state
    }

    override fun bootHint() = "A launchd agent starts when you log in: turn on automatic login (System Settings → Users & Groups) so it starts after a reboot."

    companion object {
        const val LABEL = "dev.kinuvo.server"
    }
}

internal class Systemd(home: File = File(System.getProperty("user.home"))) : ServiceManager {
    override val file = File(home, ".config/systemd/user/$UNIT")

    override fun render(spec: ServiceSpec) = buildString {
        appendLine("[Unit]")
        appendLine("Description=Kinuvo server")
        appendLine("After=network-online.target")
        appendLine()
        appendLine("[Service]")
        appendLine("ExecStart=${spec.command.joinToString(" ", transform = ::quote)}")
        spec.env.forEach { (k, v) -> appendLine("Environment=${quote("$k=$v")}") }
        appendLine("Restart=always")
        appendLine("RestartSec=10")
        appendLine("StandardOutput=append:${spec.log}")
        appendLine("StandardError=append:${spec.log}")
        appendLine()
        appendLine("[Install]")
        appendLine("WantedBy=default.target")
    }

    override fun install(spec: ServiceSpec) {
        file.parentFile.mkdirs()
        file.writeText(render(spec))
        sh("systemctl", "--user", "daemon-reload").orFail()
        sh("systemctl", "--user", "enable", UNIT).orFail()
        sh("systemctl", "--user", "restart", UNIT).orFail()
    }

    override fun uninstall() {
        sh("systemctl", "--user", "disable", "--now", UNIT)
        file.delete()
        sh("systemctl", "--user", "daemon-reload")
    }

    override fun restart() = sh("systemctl", "--user", "restart", UNIT).orFail()

    override fun state(): String {
        val state = sh("systemctl", "--user", "is-active", UNIT).out.trim().ifEmpty { "unknown" }
        val pid = sh("systemctl", "--user", "show", "-p", "MainPID", "--value", UNIT).out.trim().takeIf { it.isNotEmpty() && it != "0" }
        return if (pid != null) "$state (pid $pid)" else state
    }

    override fun bootHint(): String? {
        val user = System.getProperty("user.name")
        val linger = sh("loginctl", "show-user", user, "-p", "Linger", "--value").out.trim()
        return if (linger == "yes") null else "Run `loginctl enable-linger $user` so it starts at boot, before you log in."
    }

    private fun quote(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    companion object {
        const val UNIT = "kinuvo.service"
    }
}

private class InstallService : CoreSuspendingCliktCommand("install") {
    private val host by option("--host", help = "Interface to listen on").default("127.0.0.1")
    private val port by option("--port").int().default(HarnessServer.DEFAULT_PORT)
    private val publicUrl by option("--public-url", help = "HTTPS origin served by a reverse proxy")
    private val computer by option("--computer", help = "Expose this desktop as a Computer through cua-driver").flag()
    private val tailscale by option("--tailscale", help = "Provide private HTTPS through Tailscale Serve and retain it across service restarts").flag()
    private val tailscalePort by option("--tailscale-port", help = "Tailscale HTTPS port").int().default(443)
    private val jar by option("--jar", help = "Executable jar to run (default: the one running this command); copied to <home>/bin/kinuvo.jar")
    private val force by option("--force", help = "Install even while another Server runs on this home; stop it first or both run Routines").flag()

    override fun help(context: Context) = "Install (or update) the service and start it. Run it again after upgrading Kinuvo."

    override suspend fun run() {
        require(!tailscale || publicUrl == null) { "Use --tailscale or --public-url, not both" }
        if (tailscale) Tailscale.requireLoopback(host)
        val home = File(kinuvoHome().toString())
        val source = File(jar ?: runningJar() ?: throw CliktError("Not running from a jar; build one with `./kotlin package -m cli -f executable-jar` and pass --jar"))
        if (!source.isFile) throw CliktError("No jar at $source")
        val installed = File(home, "bin/kinuvo.jar").apply { parentFile.mkdirs() }
        val log = File(home, "logs/server.log")
        ownerOnly(log)
        val env = listOfNotNull("PATH", "KINUVO_HOME", "LANG", "KINUVO_PUBLIC_URL", "KINUVO_TRUSTED_PROXIES", "DISPLAY", "WAYLAND_DISPLAY", "XAUTHORITY", "XDG_RUNTIME_DIR", "DBUS_SESSION_BUS_ADDRESS").mapNotNull { k -> System.getenv(k)?.let { k to it } }.toMap()
        val java = File(System.getProperty("java.home"), "bin/java").path
        val manager = ServiceManager.current()
        val servicePid = Regex("""pid (\d+)""").find(manager.state())?.groupValues?.get(1)?.toLong()
        LocalServer.read(kinuvoHome())?.takeIf { it.alive && it.pid != servicePid && !force }?.let {
            throw CliktError("A Server is already running on ${home.path} (pid ${it.pid}); stop it first, or pass --force")
        }
        val route = if (tailscale) Tailscale().prepare(port, tailscalePort) else null
        try {
            route?.enable()
            if (source.canonicalFile != installed.canonicalFile) Files.copy(source.toPath(), installed.toPath(), StandardCopyOption.REPLACE_EXISTING)
            val serviceEnv = if (tailscale) env - "KINUVO_PUBLIC_URL" else env + publicUrl?.let { mapOf("KINUVO_PUBLIC_URL" to it) }.orEmpty()
            manager.install(ServiceSpec(java, installed.path, if (tailscale) "127.0.0.1" else host, port, log.path, serviceEnv, computer, tailscale, tailscalePort))
        } catch (e: Throwable) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { runCatching { route?.rollback() }.exceptionOrNull()?.let(e::addSuppressed) }
            throw e
        }
        echo("Installed ${manager.file} (${manager.state()})")
        route?.let { echo("Tailscale HTTPS: ${it.url} (private to your tailnet)") }
        echo("Logs: ${log.path}")
        manager.bootHint()?.let { echo(it) }
    }

    /** `java -jar X` puts only X on the class path. */
    private fun runningJar(): String? = System.getProperty("java.class.path").takeIf { File.pathSeparator !in it && it.endsWith(".jar") }
}

private class UninstallService : CoreSuspendingCliktCommand("uninstall") {
    override fun help(context: Context) = "Stop the service and remove it."

    override suspend fun run() {
        val manager = ServiceManager.current()
        if (!manager.file.exists()) throw CliktError("No service installed (${manager.file})")
        manager.uninstall()
        echo("Removed ${manager.file}")
    }
}

private class ServiceStatus : CoreSuspendingCliktCommand("status") {
    override fun help(context: Context) = "Show whether the service is installed and running, and the Server it runs."

    override suspend fun run() {
        val manager = ServiceManager.current()
        val local = LocalServer.read(kinuvoHome())?.takeIf { it.alive }
        echo(table(listOf("ITEM", "VALUE"), listOf(
            listOf("service", if (manager.file.exists()) manager.state() else "not installed"),
            listOf("file", manager.file.path),
            listOf("server", local?.let { "pid ${it.pid}, port ${it.port}" } ?: "not running"),
            listOf("logs", File(kinuvoHome().toString(), "logs/server.log").path),
        )))
        if (manager.file.exists()) manager.bootHint()?.let { echo(it) }
    }
}

private class RestartService : CoreSuspendingCliktCommand("restart") {
    override fun help(context: Context) = "Restart the service, e.g. after changing settings that need a restart."

    override suspend fun run() {
        val manager = ServiceManager.current()
        if (!manager.file.exists()) throw CliktError("No service installed; run `kinuvo service install`")
        manager.restart()
        echo("Restarted (${manager.state()})")
    }
}

private class ServiceLogs : CoreSuspendingCliktCommand("logs") {
    private val follow by option("-f", "--follow").flag()
    private val lines by option("-n", "--lines").int().default(100)

    override fun help(context: Context) = "Print the end of the service's log."

    override suspend fun run() {
        val log = File(kinuvoHome().toString(), "logs/server.log")
        if (!log.exists()) throw CliktError("No log yet at $log")
        val args = listOf("tail", "-n", lines.toString()) + (if (follow) listOf("-f") else emptyList()) + log.path
        ProcessBuilder(args).inheritIO().start().waitFor()
    }
}

/** Creates [log] readable by the owner only, in an owner-only folder: it holds pairing codes. */
private fun ownerOnly(log: File) {
    log.parentFile.mkdirs()
    if (!log.exists()) log.createNewFile()
    runCatching {
        Files.setPosixFilePermissions(log.parentFile.toPath(), PosixFilePermissions.fromString("rwx------"))
        Files.setPosixFilePermissions(log.toPath(), PosixFilePermissions.fromString("rw-------"))
    }
}

internal data class Sh(val code: Int, val out: String) {
    fun orFail() {
        if (code != 0) throw CliktError(out.trim().ifEmpty { "exit code $code" })
    }
}

internal fun sh(vararg command: String): Sh = runCatching {
    val p = ProcessBuilder(*command).redirectErrorStream(true).start()
    val out = p.inputStream.bufferedReader().readText()
    Sh(p.waitFor(), out)
}.getOrElse { Sh(-1, it.message ?: it.toString()) }

private fun xml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
