package kinuvo.plugins.caldav

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/** One content line, `NAME;PARAMS:value`, unfolded. */
internal data class ContentLine(val name: String, val params: String, val value: String) {
    override fun toString() = name + params + ":" + value

    companion object {
        fun parse(line: String): ContentLine {
            var quoted = false
            val colon = line.indices.firstOrNull { i -> if (line[i] == '"') quoted = !quoted; !quoted && line[i] == ':' } ?: line.length
            val head = line.substring(0, colon)
            val semi = head.indexOf(';').let { if (it < 0) head.length else it }
            return ContentLine(head.substring(0, semi).uppercase(), head.substring(semi), line.substring(minOf(colon + 1, line.length)))
        }
    }
}

/**
 * A calendar object holding a VTODO, edited in place so properties Kinuvo doesn't know (alarms, categories, other apps' own)
 * are kept as they were. Only the main VTODO is read and changed; a recurring task's overrides (with RECURRENCE-ID) stay untouched.
 */
internal class ICalendar(lines: List<String>) {
    private val lines = lines.toMutableList()

    /** The main VTODO's lines between BEGIN and END. */
    private val todo: IntRange get() {
        val todos = lines.indices.filter { lines[it].equals("BEGIN:VTODO", true) }.map(::block)
        check(todos.isNotEmpty()) { "Not a task: no VTODO" }
        return todos.firstOrNull { range -> own(range).none { it.second.name == "RECURRENCE-ID" } } ?: todos.first()
    }

    private fun block(start: Int): IntRange {
        var depth = 0
        for (i in start + 1 until lines.size) {
            val line = lines[i]
            if (line.startsWith("BEGIN:", true)) depth++
            else if (line.startsWith("END:", true)) {
                if (depth == 0) return start + 1 until i
                depth--
            }
        }
        error("Unclosed VTODO")
    }

    /** A VTODO's own properties, not those of components inside it (e.g. VALARM). */
    private fun own(range: IntRange = todo): List<Pair<Int, ContentLine>> {
        var depth = 0
        return range.mapNotNull { i ->
            val line = lines[i]
            when {
                line.startsWith("BEGIN:", true) -> { depth++; null }
                line.startsWith("END:", true) -> { depth--; null }
                depth == 0 -> i to ContentLine.parse(line)
                else -> null
            }
        }
    }

    fun get(name: String): ContentLine? = own().firstOrNull { it.second.name == name }?.second

    fun text(name: String) = get(name)?.value?.let(::unescape).orEmpty()

    /** Sets [name] to [line], or removes it when null, replacing every occurrence. */
    fun set(name: String, line: ContentLine?) {
        val at = own().filter { it.second.name == name }.map { it.first }
        at.asReversed().forEach { lines.removeAt(it) }
        if (line != null) lines.add(at.firstOrNull() ?: (todo.last + 1), line.toString())
    }

    fun setText(name: String, value: String?) = set(name, value?.takeIf { it.isNotEmpty() }?.let { ContentLine(name, "", escape(it)) })

    val done get() = get("STATUS")?.value.equals("COMPLETED", true) || get("COMPLETED") != null

    /** The due date; a time is dropped, and a UTC time is first moved to [zone]. */
    fun due(zone: TimeZone): LocalDate? {
        val value = get("DUE")?.value?.trim() ?: return null
        if (value.length < 8) return null
        val date = LocalDate(value.substring(0, 4).toInt(), value.substring(4, 6).toInt(), value.substring(6, 8).toInt())
        if (!value.endsWith("Z") || value.length < 15) return date
        val time = LocalDateTime(date.year, date.month, date.day, value.substring(9, 11).toInt(), value.substring(11, 13).toInt())
        return time.toInstant(TimeZone.UTC).toLocalDateTime(zone).date
    }

    /**
     * Sets the due date as a date, or at the end of that day when DTSTART has a time, since both must be of one kind. A
     * DTSTART after the new date is dropped.
     */
    fun setDue(date: LocalDate?) {
        if (date == null) return set("DUE", null)
        if (get("DTSTART")?.value?.take(8)?.let { it > basic(date) } == true) set("DTSTART", null)
        val start = get("DTSTART")?.takeIf { it.value.getOrNull(8) == 'T' }
        set("DUE", if (start == null) ContentLine("DUE", ";VALUE=DATE", basic(date))
            else ContentLine("DUE", start.params, basic(date) + "T235959" + if (start.value.endsWith("Z")) "Z" else ""))
    }

    fun setDone(done: Boolean, now: Instant) {
        set("STATUS", ContentLine("STATUS", "", if (done) "COMPLETED" else "NEEDS-ACTION"))
        set("COMPLETED", if (done) ContentLine("COMPLETED", "", stamp(now)) else null)
        set("PERCENT-COMPLETE", if (done) ContentLine("PERCENT-COMPLETE", "", "100") else null)
    }

    fun touch(now: Instant) {
        set("DTSTAMP", ContentLine("DTSTAMP", "", stamp(now)))
        set("LAST-MODIFIED", ContentLine("LAST-MODIFIED", "", stamp(now)))
    }

    override fun toString() = lines.joinToString("") { fold(it) + "\r\n" }

    companion object {
        fun parse(text: String) = ICalendar(text.replace("\r\n", "\n").replace(Regex("\n[ \t]"), "").split("\n").filter { it.isNotEmpty() })

        fun todo(uid: String, title: String, notes: String, due: LocalDate?, now: Instant) = ICalendar(listOf(
            "BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//Kinuvo//Tasks//EN", "BEGIN:VTODO", "UID:$uid", "END:VTODO", "END:VCALENDAR",
        )).apply {
            set("CREATED", ContentLine("CREATED", "", stamp(now)))
            touch(now)
            setText("SUMMARY", title)
            setText("DESCRIPTION", notes)
            setDue(due)
            set("STATUS", ContentLine("STATUS", "", "NEEDS-ACTION"))
        }

        fun stamp(now: Instant) = now.toString().replace("-", "").replace(":", "").substringBefore('.').removeSuffix("Z") + "Z"

        private fun basic(date: LocalDate) = date.toString().replace("-", "")

        fun escape(text: String) = text.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace("\r\n", "\n").replace("\n", "\\n")

        fun unescape(text: String) = Regex("""\\([\\;,nN])""").replace(text) { if (it.groupValues[1].equals("n", true)) "\n" else it.groupValues[1] }

        /** [line] folded so no line is longer than 75 bytes, without splitting a character. */
        private fun fold(line: String): String {
            if (line.encodeToByteArray().size <= 75) return line
            val out = StringBuilder()
            var bytes = 0
            var limit = 75
            for (c in line) {
                val size = c.toString().encodeToByteArray().size.takeIf { !c.isSurrogate() } ?: 2
                if (bytes + size > limit && !c.isLowSurrogate()) {
                    out.append("\r\n ")
                    bytes = 0
                    limit = 74
                }
                out.append(c)
                bytes += size
            }
            return out.toString()
        }
    }
}
