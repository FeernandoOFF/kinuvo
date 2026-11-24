package kinuvo.diagnostics

import co.touchlab.stately.concurrency.Synchronizable
import co.touchlab.stately.concurrency.synchronize
import kotlinx.io.Sink
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString

/**
 * One process's log: `name.log` plus up to [keep] - 1 older files (`name.1.log`, newest first), so it never exceeds
 * [keep] * [maxBytes]. Writing never throws; a line that can't be written is dropped.
 */
class RotatingLog(private val dir: Path, private val name: String, private val maxBytes: Long = 1L shl 20, private val keep: Int = 4) {
    private val lock = Synchronizable()
    private var sink: Sink? = null
    private var size = 0L

    init { require(keep >= 1 && maxBytes > 0) }

    fun append(line: String) {
        val bytes = line.encodeToByteArray().size + 1L
        lock.synchronize {
            try {
                if (sink == null) open()
                if (size > 0 && size + bytes > maxBytes) rotate()
                requireNotNull(sink).apply { writeString(line); writeString("\n"); flush() }
                size += bytes
            } catch (_: Exception) {
                runCatching { sink?.close() }
                sink = null
            }
        }
    }

    /** The log's files, newest first. */
    fun files(): List<Path> = (0 until keep).map(::file).filter { SystemFileSystem.exists(it) }

    fun close() = lock.synchronize {
        runCatching { sink?.close() }
        sink = null
    }

    private fun file(index: Int) = Path(dir, if (index == 0) "$name.log" else "$name.$index.log")

    private fun open() {
        SystemFileSystem.createDirectories(dir)
        restrictToOwner(dir)
        size = SystemFileSystem.metadataOrNull(file(0))?.size ?: 0L
        sink = SystemFileSystem.sink(file(0), append = true).buffered()
        restrictToOwner(file(0))
    }

    private fun rotate() {
        requireNotNull(sink).close()
        sink = null
        SystemFileSystem.delete(file(keep - 1), mustExist = false)
        for (index in keep - 2 downTo 0) if (SystemFileSystem.exists(file(index))) SystemFileSystem.atomicMove(file(index), file(index + 1))
        open()
    }
}
