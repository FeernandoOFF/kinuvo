package kinuvo.app.surface

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant
import kotlin.math.round

fun sliderValue(value: Double, min: Double, max: Double, divisions: Int? = null): Double? {
    if (!min.isFinite() || !max.isFinite() || max <= min || !(max - min).isFinite()) return null
    val clamped = (value.takeIf { it.isFinite() } ?: min).coerceIn(min, max)
    val count = divisions?.coerceIn(1, 1000) ?: return clamped
    return (min + round((clamped - min) / (max - min) * count) / count * (max - min)).coerceIn(min, max)
}

fun dateTimeValue(value: String, date: Boolean, time: Boolean, min: String? = null, max: String? = null): String? {
    fun dateTime(raw: String): LocalDateTime = runCatching { LocalDateTime.parse(raw) }
        .getOrElse { Instant.parse(raw).toLocalDateTime(TimeZone.UTC) }
    fun clockTime(raw: String): LocalTime = runCatching { LocalTime.parse(raw) }
        .getOrElse { Instant.parse("1970-01-01T$raw").toLocalDateTime(TimeZone.UTC).time }
    fun parse(raw: String): String = when {
        date && time -> runCatching { LocalDateTime.parse(raw).toString() }.getOrElse { Instant.parse(raw).toString() }
        date -> LocalDate.parse(raw).toString()
        time -> runCatching { LocalTime.parse(raw).toString() }
            .getOrElse { Instant.parse("1970-01-01T$raw").toString().substringAfter('T') }
        else -> error("Choose date or time")
    }
    fun compare(left: String, right: String): Int = when {
        date && time -> when {
            'T' in right -> dateTime(left).compareTo(dateTime(right))
            ':' in right -> dateTime(left).time.compareTo(clockTime(right))
            else -> dateTime(left).date.compareTo(LocalDate.parse(right))
        }
        date -> LocalDate.parse(left).compareTo(LocalDate.parse(right.substringBefore('T')))
        else -> clockTime(left).compareTo(clockTime(right.substringAfter('T')))
    }
    return runCatching {
        if (value.isEmpty()) return ""
        val result = parse(value)
        require(min.isNullOrBlank() || compare(result, min) >= 0)
        require(max.isNullOrBlank() || compare(result, max) <= 0)
        result
    }.getOrNull()
}
