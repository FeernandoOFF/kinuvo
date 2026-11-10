package kinuvo.harness.core.routines

import kinuvo.protocol.ActiveHours
import kinuvo.protocol.Routine
import kinuvo.protocol.Trigger
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.number
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/** When Routines fire: the next slot of a [Trigger], skipping slots outside [ActiveHours]. */
object Schedule {
    private const val MAX_SKIPS = 100_000

    /** The first slot strictly after [after], or null when the Routine won't fire again. */
    fun next(routine: Routine, after: Long): Long? {
        val zone = TimeZone.of(routine.timeZone)
        val (trigger, hours) = routine.trigger to routine.activeHours
        if (trigger is Trigger.Once) return trigger.at.takeIf { it > after }
        var slot = nextSlot(trigger, routine.createdAt, zone, after) ?: return null
        repeat(MAX_SKIPS) {
            if (hours == null || hours.contains(slot, zone)) return slot
            slot = nextSlot(trigger, routine.createdAt, zone, slot) ?: return null
        }
        return null
    }

    /** The latest slot at or before [now], starting from [from] (a slot); [from] itself when none is later. */
    fun latest(routine: Routine, from: Long, now: Long): Long {
        var slot = from
        repeat(MAX_SKIPS) {
            val n = next(routine, slot) ?: return slot
            if (n > now) return slot
            slot = n
        }
        return slot
    }

    /** How late a slot may still run: half the gap to the next one, between 1 minute and 2 hours. */
    fun grace(routine: Routine, slot: Long): Long {
        val gap = next(routine, slot)?.let { it - slot } ?: return 2 * HOUR
        return (gap / 2).coerceIn(MINUTE, 2 * HOUR)
    }

    fun validate(trigger: Trigger) {
        when (trigger) {
            is Trigger.Once -> Unit
            is Trigger.Every -> require(trigger.minutes >= 1) { "every must be at least 1 minute" }
            is Trigger.Cron -> CronExpr(trigger.expr)
        }
    }

    private fun nextSlot(trigger: Trigger, anchor: Long, zone: TimeZone, after: Long): Long? = when (trigger) {
        is Trigger.Once -> trigger.at.takeIf { it > after }
        is Trigger.Every -> {
            val period = trigger.minutes * MINUTE
            if (after < anchor) anchor else anchor + ((after - anchor) / period + 1) * period
        }
        is Trigger.Cron -> CronExpr(trigger.expr).next(Instant.fromEpochMilliseconds(after), zone)?.toEpochMilliseconds()
    }

    private const val MINUTE = 60_000L
    private const val HOUR = 60 * MINUTE
}

fun ActiveHours.contains(at: Long, zone: TimeZone): Boolean {
    val time = Instant.fromEpochMilliseconds(at).toLocalDateTime(zone).time
    val from = LocalTime.parse(start)
    val to = LocalTime.parse(end)
    return if (from <= to) time >= from && time < to else time >= from || time < to
}

/** A five-field cron expression: numbers, `*`, lists, ranges, steps, and month/day names. */
class CronExpr(expr: String) {
    private val minutes: Set<Int>
    private val hours: Set<Int>
    private val days: Set<Int>
    private val months: Set<Int>
    private val weekdays: Set<Int>
    /** Whether a day may match either field, as when both are restricted; a field starting with `*` makes both apply. */
    private val eitherDay: Boolean

    init {
        val fields = expr.trim().split(Regex("\\s+"))
        require(fields.size == 5) { "cron needs 5 fields (minute hour day month weekday), got '$expr'" }
        minutes = field(fields[0], 0, 59)
        hours = field(fields[1], 0, 23)
        days = field(fields[2], 1, 31)
        months = field(fields[3], 1, 12, MONTHS)
        weekdays = field(fields[4], 0, 7, WEEKDAYS).map { it % 7 }.toSet()
        eitherDay = !fields[2].startsWith("*") && !fields[4].startsWith("*")
    }

    /**
     * The first matching minute strictly after [after], searching up to five years ahead. Matching walks local time, so
     * a time the clocks skip runs that much later (01:30 in a 01:00→02:00 gap runs at 02:30), and one they repeat runs once.
     */
    fun next(after: Instant, zone: TimeZone): Instant? {
        val start = after.toLocalDateTime(zone)
        var t = LocalDateTime(start.date, LocalTime(start.hour, start.minute)).plusMinute()
        val limit = start.date.plus(5, DateTimeUnit.YEAR)
        while (t.date < limit) {
            t = when {
                t.month.number !in months -> LocalDate(t.year, t.month, 1).plus(1, DateTimeUnit.MONTH).atMidnight()
                !dayMatches(t.date) -> t.date.plus(1, DateTimeUnit.DAY).atMidnight()
                t.hour !in hours -> if (t.hour == 23) t.date.plus(1, DateTimeUnit.DAY).atMidnight() else LocalDateTime(t.date, LocalTime(t.hour + 1, 0))
                t.minute !in minutes -> t.plusMinute()
                else -> t.toInstant(zone).let { if (it > after) return it else t.plusMinute() }
            }
        }
        return null
    }

    private fun dayMatches(date: LocalDate): Boolean {
        val day = date.day in days
        val weekday = date.dayOfWeek.isoDayNumber % 7 in weekdays
        return if (eitherDay) day || weekday else day && weekday
    }

    private fun LocalDate.atMidnight() = LocalDateTime(this, LocalTime(0, 0))

    private fun LocalDateTime.plusMinute(): LocalDateTime = when {
        minute < 59 -> LocalDateTime(date, LocalTime(hour, minute + 1))
        hour < 23 -> LocalDateTime(date, LocalTime(hour + 1, 0))
        else -> date.plus(1, DateTimeUnit.DAY).atMidnight()
    }

    private fun field(text: String, min: Int, max: Int, names: List<String> = emptyList()): Set<Int> = text.split(",").flatMap { part ->
        val (range, step) = part.split("/").let { it[0] to (it.getOrNull(1)?.toIntOrNull() ?: 1) }
        require(step >= 1) { "Bad step in '$part'" }
        val (from, to) = when {
            range == "*" -> min to max
            "-" in range -> range.split("-").let { value(it[0], names, min) to value(it[1], names, min) }
            else -> value(range, names, min).let { it to if ("/" in part) max else it }
        }
        require(from in min..max && to in min..max && from <= to) { "'$part' is out of range $min-$max" }
        (from..to step step).toList()
    }.toSet()

    private fun value(text: String, names: List<String>, min: Int): Int =
        text.toIntOrNull() ?: names.indexOf(text.uppercase()).takeIf { it >= 0 }?.plus(min) ?: error("Bad cron value '$text'")

    private companion object {
        val MONTHS = listOf("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC")
        val WEEKDAYS = listOf("SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT")
    }
}
