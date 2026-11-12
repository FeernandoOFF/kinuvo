package kinuvo.cli

import com.github.ajalt.clikt.command.CoreSuspendingCliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import kinuvo.server.jvm.HarnessServer
import kinuvo.server.jvm.LocalServer
import kinuvo.server.jvm.ServeException
import kinuvo.server.jvm.ServeOptions
import kinuvo.server.jvm.LoginServiceSettings
import kinuvo.server.jvm.Tailscale
import kotlinx.serialization.json.Json
import kinuvo.server.jvm.kinuvoHome
import kinuvo.protocol.device.Platform
import kinuvo.protocol.device.currentPlatform
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import kotlinx.coroutines.NonCancellable

internal class ServiceCommands : Group(
    "service", "Run the Server as a service of this machine's user, started at login and restarted when it exits (launchd or systemd).",
    InstallService(), UninstallService(), ServiceStatus(), RestartService(), ServiceLogs(),
)

/** What the service runs: [jar] with this JVM, serving on [host]:[port], logging to [log]. */
internal data class ServiceSpec(val java: String, val jar: String, val host: String, val port: Int, val log: String, val env: Map<String, String>, val computer: Boolean = false, val tailscale: Boolean = false, val tailscalePort: Int = 443) {
    val home get() = File(log).parentFile.parentFile.path
    val outputLog get() = File(File(log).parentFile, "service-output.log").path
    val command get() = listOf(java, "-Dlogback.configurationFile=logback-service.xml", "-Dkinuvo.log.file=$log", "-jar", jar, "serve", "--host", host, "--port", port.toString()) +
        (if (computer) listOf("--computer") else emptyList()) +
        (if (tailscale) listOf("--tailscale", "--tailscale-port", tailscalePort.toString()) else emptyList())
}

/** The user's launchd agent or systemd unit for the Server. */
internal interface ServiceManager {
    val file: File
    /** What the user sees as the service's location. */
    val label: String get() = file.path
    /** Whether the service runs the CLI's jar, copied to `<home>/bin/kinuvo.jar`. */
    val runsJar: Boolean get() = true
    fun render(spec: ServiceSpec): String
    fun installed(): Boolean = file.exists()
    fun install(spec: ServiceSpec)
    fun uninstall()
    fun restart()
    /** E.g. `running (pid 123)`, `stopped`. */
    fun state(): String
    /** Something the user still has to do for the service to start at boot; null when nothing. */
    fun bootHint(): String? = null

    companion object {
        /** With [install], the installed desktop app's service wins on macOS; otherwise whichever is installed. */
        fun current(install: Boolean = false): ServiceManager {
            return when (currentPlatform) {
                Platform.MacOS -> {
                    val legacy = Launchd()
                    val app = DesktopApp.find()?.let(::AppService) ?: return legacy
                    if (install || !legacy.file.exists() || app.installed()) app else legacy
                }
                Platform.Linux -> Systemd()
                else -> throw CliktError("Services are supported on macOS (launchd) and Linux (systemd), not ${currentPlatform.displayName}")
            }
        }
    }
}

/** The installed `Kinuvo.app`, whose bundled launch agent runs the Server under the app's bundle identity. */
internal object DesktopApp {
    const val BUNDLE_ID = "dev.kinuvo.desktop"

    fun find(home: File = File(System.getProperty("user.home")), system: File = File("/Applications"), run: (List<String>) -> Sh = { sh(*it.toTypedArray()) }): File? {
        val known = listOfNotNull(System.getenv("KINUVO_APP")?.let(::File), File(system, "Kinuvo.app"), File(home, "Applications/Kinuvo.app"))
        val launchServices by lazy { run(listOf("osascript", "-e", "POSIX path of (path to application id \"$BUNDLE_ID\")")).takeIf { it.code == 0 }?.out?.trim()?.let(::File) }
        return (known.asSequence() + generateSequence { launchServices }.take(1)).firstOrNull { AppService.bundlesAgent(it) }
    }
}

/** The Server as a login item of `Kinuvo.app`: macOS privacy permissions belong to its bundle, not to a bare `java`. */
internal class AppService(
    private val app: File,
    private val home: File = File(System.getProperty("user.home")),
    private val run: (List<String>) -> Sh = { sh(*it.toTypedArray()) },
) : ServiceManager {
    private val launchd = Launchd(home, run)
    override val file = File(app, AGENT)
    override val label get() = app.path
    override val runsJar = false

    override fun render(spec: ServiceSpec): String {
        val options = ServeOptions(spec.host, spec.port, spec.env["KINUVO_PUBLIC_URL"], spec.env["KINUVO_TRUSTED_PROXIES"], spec.tailscale, spec.tailscalePort)
        return Json.encodeToString(LoginServiceSettings.serializer(), LoginServiceSettings(spec.home, options))
    }

    override fun install(spec: ServiceSpec) {
        val settings = Json.decodeFromString(LoginServiceSettings.serializer(), render(spec))
        if (launchd.file.exists()) launchd.uninstall()
        LoginServiceSettings.write(settings, home.path)
        // Registering can fail just after the previous Server goes, so try a few times.
        var result = agent("register")
        for (attempt in 1..4) {
            if (result.code == 0) break
            Thread.sleep(1_000)
            result = agent("register")
        }
        result.orFail()
    }

    override fun uninstall() {
        agent("unregister").orFail()
        if (launchd.file.exists()) launchd.uninstall()
        File(LoginServiceSettings.file(home.path).toString()).delete()
    }

    /** macOS also reports an old-style agent with the same label as registered, so it only counts once that one is gone. */
    override fun installed() = !launchd.file.exists() && status() in setOf("enabled", "requiresApproval")

    override fun restart() = launchd.restart()

    override fun state() = when (val status = status()) {
        "enabled" -> launchd.state()
        "requiresApproval" -> "waiting for your approval"
        "notRegistered" -> "not installed"
        else -> status
    }

    override fun bootHint() = if (status() == "requiresApproval") "Allow Kinuvo in System Settings → General → Login Items & Extensions, so the service can start." else launchd.bootHint()

    private fun status() = agent("status").takeIf { it.code == 0 }?.out?.lines()?.lastOrNull { it.isNotBlank() }?.trim() ?: "unknown"

    private fun agent(command: String) = run(listOf(File(app, EXECUTABLE).path, "--service", command))

    companion object {
        const val AGENT = "Contents/Library/LaunchAgents/${Launchd.LABEL}.plist"
        const val EXECUTABLE = "Contents/MacOS/Kinuvo"

        fun bundlesAgent(app: File) = File(app, EXECUTABLE).canExecute() && File(app, AGENT).isFile
    }
}

internal class Launchd(
    private val home: File = File(System.getProperty("user.home")),
    private val run: (List<String>) -> Sh = { sh(*it.toTypedArray()) },
) : ServiceManager {
    private fun sh(vararg command: String) = run(command.toList())

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
        appendLine("  <key>StandardOutPath</key><string>${xml(spec.outputLog)}</string>")
        appendLine("  <key>StandardErrorPath</key><string>${xml(spec.outputLog)}</string>")
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
        appendLine("StandardOutput=append:${spec.outputLog}")
        appendLine("StandardError=append:${spec.outputLog}")
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
        val manager = ServiceManager.current(install = true)
        if (computer && !manager.runsJar) throw CliktError("--computer isn't supported by the Kinuvo app's service yet; run `kinuvo serve --computer` instead")
        val installed = File(home, "bin/kinuvo.jar")
        val source = if (manager.runsJar) File(jar ?: runningJar() ?: throw CliktError("Not running from a jar; build one with `./kotlin package -m cli -f executable-jar` and pass --jar")) else null
        if (source != null && !source.isFile) throw CliktError("No jar at $source")
        installed.parentFile.mkdirs()
        val log = File(home, "logs/server.log")
        ownerOnly(log)
        ownerOnly(File(home, "logs/service-output.log"))
        val env = listOfNotNull("PATH", "KINUVO_HOME", "LANG", "KINUVO_LOG_LEVEL", "KINUVO_PUBLIC_URL", "KINUVO_TRUSTED_PROXIES", "DISPLAY", "WAYLAND_DISPLAY", "XAUTHORITY", "XDG_RUNTIME_DIR", "DBUS_SESSION_BUS_ADDRESS").mapNotNull { k -> System.getenv(k)?.let { k to it } }.toMap()
        val java = File(System.getProperty("java.home"), "bin/java").path
        val servicePid = Regex("""pid (\d+)""").find(manager.state())?.groupValues?.get(1)?.toLong()
        LocalServer.read(kinuvoHome())?.takeIf { it.alive && it.pid != servicePid && !force }?.let {
            throw CliktError("A Server is already running on ${home.path} (pid ${it.pid}); stop it first, or pass --force")
        }
        val route = if (tailscale) serveErrors { Tailscale().prepare(port, tailscalePort) } else null
        try {
            serveErrors { route?.enable() }
            if (source != null && source.canonicalFile != installed.canonicalFile) Files.copy(source.toPath(), installed.toPath(), StandardCopyOption.REPLACE_EXISTING)
            val serviceEnv = if (tailscale) env - "KINUVO_PUBLIC_URL" else env + publicUrl?.let { mapOf("KINUVO_PUBLIC_URL" to it) }.orEmpty()
            manager.install(ServiceSpec(java, installed.path, if (tailscale) "127.0.0.1" else host, port, log.path, serviceEnv, computer, tailscale, tailscalePort))
        } catch (e: Throwable) {
            kotlinx.coroutines.withContext(NonCancellable) { runCatching { route?.rollback() }.exceptionOrNull()?.let(e::addSuppressed) }
            throw e
        }
        echo("Installed ${manager.label} (${manager.state()})")
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
        if (!manager.installed()) throw CliktError("No service installed (${manager.label})")
        manager.uninstall()
        echo("Removed ${manager.label}")
    }
}

private class ServiceStatus : CoreSuspendingCliktCommand("status") {
    override fun help(context: Context) = "Show whether the service is installed and running, and the Server it runs."

    override suspend fun run() {
        val manager = ServiceManager.current()
        val local = LocalServer.read(kinuvoHome())?.takeIf { it.alive }
        echo(table(listOf("ITEM", "VALUE"), listOf(
            listOf("service", if (manager.installed()) manager.state() else "not installed"),
            listOf("file", manager.label),
            listOf("server", local?.let { "pid ${it.pid}, port ${it.port}" } ?: "not running"),
            listOf("logs", File(kinuvoHome().toString(), "logs/server.log").path),
        )))
        if (manager.installed()) manager.bootHint()?.let { echo(it) }
    }
}

private class RestartService : CoreSuspendingCliktCommand("restart") {
    override fun help(context: Context) = "Restart the service, e.g. after changing settings that need a restart."

    override suspend fun run() {
        val manager = ServiceManager.current()
        if (!manager.installed()) throw CliktError("No service installed; run `kinuvo service install`")
        manager.restart()
        echo("Restarted (${manager.state()})")
    }
}

private class ServiceLogs : CoreSuspendingCliktCommand("logs") {
    private val follow by option("-f", "--follow").flag()
    private val lines by option("-n", "--lines").int().default(100)

    override fun help(context: Context) = "Print the end of the service's logs."

    override suspend fun run() {
        val directory = File(kinuvoHome().toString(), "logs")
        val logs = listOf(File(directory, "server.log"), File(directory, "service-output.log")).filter { it.exists() }
        if (logs.isEmpty()) throw CliktError("No logs yet at $directory")
        val args = listOf("tail", "-n", lines.toString()) + (if (follow) listOf("-F") else emptyList()) + logs.map { it.path }
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

/** Shows the Server's own problems the way commands show theirs. */
internal suspend fun <T> serveErrors(block: suspend () -> T): T = try { block() } catch (e: ServeException) { throw CliktError(e.message) }

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
