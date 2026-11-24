package kinuvo.diagnostics

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlin.time.Clock

/** A crash the user hasn't been asked about yet. */
data class PendingCrash(val at: Long, val summary: String)

/** The crash files of one data directory: `crash-<epoch ms>.txt`, written synchronously as the process dies. */
class CrashReports(private val dir: Path, private val app: AppInfo? = null, private val keep: Int = 10) {
    private val seen = Path(dir, ".seen")

    fun record(thread: String, error: Throwable, at: Long = Clock.System.now().toEpochMilliseconds()) {
        runCatching {
            SystemFileSystem.createDirectories(dir)
            restrictToOwner(dir)
            val file = Path(dir, "crash-$at.txt")
            val text = buildString {
                appendLine("Time: $at")
                appendLine("Thread: $thread")
                app?.let { appendLine("App: ${it.version} (${it.build}) on ${it.platform}, ${it.os}") }
                appendLine(error.stackTraceToString().lineSequence().take(MAX_LINES).joinToString("\n") { it.take(MAX_LINE) })
            }
            SystemFileSystem.sink(file).buffered().use { it.writeString(Redaction.apply(text)) }
            restrictToOwner(file)
        }
    }

    /** The newest crash since the user was last asked, if any. */
    fun pending(): PendingCrash? {
        val newest = files().firstOrNull() ?: return null
        val at = timeOf(newest) ?: return null
        if (at <= (read(seen)?.trim()?.toLongOrNull() ?: 0L)) return null
        val summary = read(newest).orEmpty().lineSequence().firstOrNull { it.isNotBlank() && !it.startsWith("Time:") && !it.startsWith("Thread:") && !it.startsWith("App:") }.orEmpty()
        return PendingCrash(at, summary.take(200))
    }

    /** Remembers that the user was asked, and drops the oldest files beyond the limit. */
    fun acknowledge() {
        runCatching {
            val newest = files().firstOrNull()?.let(::timeOf) ?: return
            SystemFileSystem.sink(seen).buffered().use { it.writeString(newest.toString()) }
            files().drop(keep).forEach { SystemFileSystem.delete(it, mustExist = false) }
        }
    }

    /** Crash files, newest first. */
    fun files(): List<Path> = runCatching { SystemFileSystem.list(dir).filter { timeOf(it) != null }.sortedByDescending { timeOf(it) } }.getOrDefault(emptyList())

    private fun timeOf(path: Path) = path.name.removePrefix("crash-").removeSuffix(".txt").takeIf { path.name.startsWith("crash-") && path.name.endsWith(".txt") }?.toLongOrNull()

    private fun read(path: Path) = runCatching { SystemFileSystem.source(path).buffered().use { it.readString() } }.getOrNull()

    private companion object {
        const val MAX_LINES = 120
        const val MAX_LINE = 500
    }
}
