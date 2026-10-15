package kinuvo.cli

import com.github.ajalt.clikt.core.CliktError
import kinuvo.protocol.Trigger
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format
import kotlinx.datetime.format.char
import kotlinx.datetime.toLocalDateTime
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlin.time.Instant

/** Aligned columns; the last one isn't padded. */
internal fun table(header: List<String>, rows: List<List<String>>): String {
    val all = listOf(header) + rows
    val widths = header.indices.map { i -> all.maxOf { it[i].length } }
    return all.joinToString("\n") { row -> row.mapIndexed { i, c -> if (i == row.lastIndex) c else c.padEnd(widths[i]) }.joinToString("  ").trimEnd() }
}

internal fun Trigger.describe(): String = when (this) {
    is Trigger.Once -> "once"
    is Trigger.Every -> when {
        minutes % 1440 == 0L -> "every ${minutes / 1440}d"
        minutes % 60 == 0L -> "every ${minutes / 60}h"
        else -> "every ${minutes}m"
    }
    is Trigger.Cron -> "cron '$expr'"
}

private val TIME = LocalDateTime.Format { date(LocalDate.Formats.ISO); char(' '); hour(); char(':'); minute() }

/** `2026-10-01 09:00` in [zone]. */
internal fun time(epochMs: Long, zone: String): String =
    Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(runCatching { TimeZone.of(zone) }.getOrDefault(TimeZone.UTC)).format(TIME)

/** [value], or stdin when it's `-`. */
internal fun textOrStdin(value: String): String = if (value == "-") generateSequence(::readlnOrNull).joinToString("\n") else value

internal fun confirm(question: String): Boolean {
    print("$question [y/N] ")
    return readlnOrNull()?.trim()?.lowercase() in setOf("y", "yes")
}

internal fun readText(path: String): String = SystemFileSystem.source(Path(path)).buffered().use { it.readString() }

/** `KEY=value` pairs as a map. */
internal fun pairs(values: List<String>, what: String): Map<String, String> = values.associate {
    val (k, v) = it.split("=", limit = 2).takeIf { p -> p.size == 2 && p[0].isNotBlank() } ?: throw CliktError("$what must look like KEY=value, got '$it'")
    k.trim() to v
}
