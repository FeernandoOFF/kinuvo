package kinuvo.diagnostics

import co.touchlab.stately.concurrency.Synchronizable
import co.touchlab.stately.concurrency.synchronize
import kotlinx.io.files.Path

/**
 * The logs and crash files of one app process, kept under [dir] (`logs/`, `crashes/`). Install once at launch; share
 * them later with [export], and offer to when [pendingCrash] says the app crashed last time.
 */
class Diagnostics(val dir: Path, val app: AppInfo) {
    private val crashes = CrashReports(Path(dir, "crashes"), app)
    private val logs = Path(dir, "logs")

    /** Starts writing this process's logs to `logs/<scope>.log` and recording its uncaught exceptions, once per process. */
    fun install(scope: String) {
        if (!claim()) return
        val writer = LogWriter(RotatingLog(logs, scope))
        installPlatform(writer, crashes)
        writer.write("INFO", "kinuvo.app", "launch version=${app.version} build=${app.build} platform=${app.platform} os=${app.os} device=${app.device} arch=${app.arch}")
        crashes.pending()?.let { writer.write("WARN", "kinuvo.app", "The previous session crashed at ${it.at}: ${it.summary}") }
    }

    fun pendingCrash(): PendingCrash? = crashes.pending()

    fun acknowledgeCrash() = crashes.acknowledge()

    /** Writes the bundle into [into] and returns its path. */
    fun export(into: Path): Path = LogBundle(logs, crashes, app).write(into)

    private companion object {
        val lock = Synchronizable()
        var installed = false

        fun claim(): Boolean = lock.synchronize { !installed.also { installed = true } }
    }
}

internal expect fun installPlatform(writer: LogWriter, crashes: CrashReports)
