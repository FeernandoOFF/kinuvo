package kinuvo.diagnostics

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.time.Clock

@Serializable
private class SystemInfo(val generatedAt: String, val app: AppInfo, val files: List<String>)

/**
 * The ZIP people share: the app's recent log files, its crash files and `system.json`. Text is redacted again here and
 * each file is cut to its most recent bytes. It holds no chats, attachments, files, configuration or databases.
 */
class LogBundle(private val logs: Path, private val crashes: CrashReports, private val app: AppInfo) {
    fun write(into: Path, now: Clock = Clock.System): Path {
        val stamp = now.now().toString().take(19).replace("-", "").replace(":", "").replace("T", "-")
        val files = linkedMapOf<String, String>()
        var budget = TOTAL_BYTES
        for (path in logFiles()) {
            if (budget <= 0) break
            val text = tail(path, minOf(FILE_BYTES, budget))
            budget -= text.length
            files["logs/${path.name}"] = text
        }
        for (path in crashes.files().take(MAX_CRASHES)) files["crashes/${path.name}"] = tail(path, CRASH_BYTES)
        val zip = ZipWriter()
        zip.add("README.txt", README.encodeToByteArray())
        zip.add("system.json", pretty.encodeToString(SystemInfo(now.now().toString(), app, files.keys.toList())).encodeToByteArray())
        files.forEach { (name, text) -> zip.add(name, Redaction.apply(text).encodeToByteArray()) }
        SystemFileSystem.createDirectories(into)
        SystemFileSystem.list(into).filter { it.name.startsWith("kinuvo-logs-") && it.name.endsWith(".zip") }.sortedByDescending { it.name }.drop(KEEP_EXPORTS - 1)
            .forEach { SystemFileSystem.delete(it, mustExist = false) }
        val file = Path(into, "kinuvo-logs-$stamp.zip")
        SystemFileSystem.sink(file).buffered().use { it.write(zip.finish()) }
        restrictToOwner(file)
        return file
    }

    /** Current files first, then older ones, so the newest events survive the size limit. */
    private fun logFiles(): List<Path> = runCatching {
        SystemFileSystem.list(logs).filter { it.name.endsWith(".log") && SystemFileSystem.metadataOrNull(it)?.isRegularFile == true }
            .sortedWith(compareBy({ it.name.removeSuffix(".log").substringAfterLast('.', "").toIntOrNull() ?: 0 }, { it.name }))
    }.getOrDefault(emptyList())

    private fun tail(path: Path, limit: Int): String = runCatching {
        val size = SystemFileSystem.metadataOrNull(path)?.size ?: 0L
        SystemFileSystem.source(path).buffered().use { source ->
            if (size > limit) {
                source.skip(size - limit)
                "[Earlier content omitted]\n" + source.readByteArray().decodeToString().substringAfter('\n')
            } else source.readByteArray().decodeToString()
        }
    }.getOrDefault("[File unavailable]")

    private companion object {
        val pretty = Json { prettyPrint = true }
        const val FILE_BYTES = 1 shl 20
        const val TOTAL_BYTES = 6 shl 20
        const val CRASH_BYTES = 64 shl 10
        const val MAX_CRASHES = 10
        const val KEEP_EXPORTS = 3
        const val README = "Kinuvo logs for support.\n\n" +
            "logs/: recent operational log lines (run, connection and error events), redacted.\n" +
            "crashes/: stack traces of crashes.\n" +
            "system.json: app version, build, platform and OS.\n\n" +
            "Not included: chats and message text, attachments, files, notes, memory, configuration, databases, API keys, tokens and passwords.\n" +
            "Secrets, e-mail addresses and URL query strings are removed on a best-effort basis. Open the files and review them before sharing.\n"
    }
}
