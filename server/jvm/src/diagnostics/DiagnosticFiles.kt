package kinuvo.server.jvm.diagnostics

import kinuvo.protocol.diagnostics.DiagnosticReport
import kinuvo.protocol.ProtocolVersion
import kinuvo.server.jvm.kinuvoHome
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import java.util.zip.GZIPInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

fun diagnosticHome(): File = File(kinuvoHome().toString())

fun privateDirectory(dir: File) {
    if (!dir.exists()) {
        Files.createDirectories(dir.toPath())
        runCatching { Files.setPosixFilePermissions(dir.toPath(), PosixFilePermissions.fromString("rwx------")) }
    }
}

fun privateFile(file: File) {
    runCatching { Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString("rw-------")) }
}

class DiagnosticFiles(val home: File = diagnosticHome(), private val redact: Redaction = Redaction()) {
    fun report(scope: String): DiagnosticReport {
        val files = linkedMapOf("system.txt" to """
            Kinuvo: ${javaClass.getResource("/kinuvo-version")?.readText()?.trim() ?: "dev"}
            Protocol: ${ProtocolVersion.Current}
            Scope: $scope
            Exported: ${Instant.now()}
            OS: ${System.getProperty("os.name")} ${System.getProperty("os.version")}
            Architecture: ${System.getProperty("os.arch")}
            Java: ${System.getProperty("java.version")} (${System.getProperty("java.vendor")})
        """.trimIndent())
        var remaining = TOTAL_BYTES
        val candidates = listOf("logs", "crashes", "traces").flatMap { folder ->
            val dir = File(home, folder)
            if (Files.isSymbolicLink(dir.toPath())) emptyList() else dir.listFiles().orEmpty().filter { file ->
                Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS) && when (folder) {
                    "traces" -> file.name.matches(Regex("spans-[0-3]\\.jsonl"))
                    "crashes" -> file.name.matches(Regex("(?:uncaught-.*|hs_err_pid[0-9]+)\\.log"))
                    else -> file.name.matches(Regex("(?:desktop|cli|server|service-output)(?:[-.][a-zA-Z0-9_.-]+)?\\.log(?:\\.[a-zA-Z0-9_.-]+)?"))
                }
            }.map { folder to it }
        }.sortedByDescending { it.second.lastModified() }.take(24)
        for ((folder, file) in candidates) {
            if (remaining <= 0) break
            val limit = minOf(FILE_BYTES, remaining)
            val content = runCatching { readTail(file, limit) }.getOrElse { "[File unavailable during export]" }
            remaining -= content.toByteArray().size
            files["$folder/${file.name.removeSuffix(".gz")}"] = redact.apply(if (file.name.startsWith("hs_err_pid")) fatalSummary(content) else content)
        }
        files["README.txt"] = "Diagnostics contain recent operational logs, crash details and Agent trace metadata.\nKnown credentials and your user-home path are redacted on a best-effort basis.\nReview every file before sharing; logs can still include private information.\nChats, attachments, environment variables, configuration, databases and memory dumps are not collected.\n"
        return DiagnosticReport(files)
    }

    fun writeZip(destination: File, reports: Map<String, DiagnosticReport>) {
        require(!destination.exists()) { "Output already exists: $destination" }
        destination.absoluteFile.parentFile?.let(::privateDirectory)
        Files.createFile(destination.toPath())
        privateFile(destination)
        try {
            ZipOutputStream(destination.outputStream()).use { zip ->
                reports.forEach { (scope, report) ->
                    require(scope.matches(Regex("[a-z-]+")))
                    require(report.files.size <= 32 && report.files.values.sumOf { it.toByteArray().size.toLong() } <= TOTAL_BYTES + 16 * 1024) { "Diagnostic report is too large" }
                    report.files.forEach { (name, content) ->
                        require(name.length <= 200 && name.matches(Regex("[a-zA-Z0-9_./-]+")) && name.split('/').all { it.isNotBlank() && it != "." && it != ".." && '\\' !in it && ':' !in it }) { "Invalid diagnostic filename" }
                        zip.putNextEntry(ZipEntry("$scope/$name"))
                        zip.write(redact.apply(content).toByteArray())
                        zip.closeEntry()
                    }
                }
            }
        } catch (e: Exception) { destination.delete(); throw e }
    }

    private fun fatalSummary(content: String): String {
        // Native crash files include memory, environment and process details beyond the failing stack.
        return content.lineSequence().takeWhile { !it.startsWith("siginfo:") && !it.startsWith("Registers:") && !it.contains("P R O C E S S") }
            .filter { it.startsWith("#") || it.startsWith("Current thread") || it.startsWith("Native frames") || it.startsWith("Java frames") || it.matches(Regex("[VCJjv]  .*")) }
            .take(100).joinToString("\n") + "\n[Only the crash header and stack frames are included]"
    }

    private fun readTail(file: File, limit: Int): String {
        if (file.extension == "gz") return GZIPInputStream(file.inputStream()).use { it.readNBytes(limit).toString(Charsets.UTF_8) }
        return RandomAccessFile(file, "r").use {
            val skipped = if (file.name.startsWith("hs_err_pid")) 0 else (it.length() - limit).coerceAtLeast(0)
            it.seek(skipped)
            val bytes = ByteArray(minOf(it.length(), limit.toLong()).toInt())
            it.readFully(bytes)
            (if (skipped > 0) "[Earlier content omitted]\n" else "") + bytes.toString(Charsets.UTF_8)
        }
    }

    companion object {
        const val FILE_BYTES = 256 * 1024
        const val TOTAL_BYTES = 2 * 1024 * 1024
    }
}
