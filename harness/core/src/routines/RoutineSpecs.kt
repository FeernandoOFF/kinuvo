package kinuvo.harness.core.routines

import kinuvo.protocol.ActiveHours
import kinuvo.protocol.Routine
import kinuvo.protocol.RoutineSpec
import kinuvo.protocol.Trigger
import kotlinx.datetime.IllegalTimeZoneException
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.time.Instant

/** [spec]'s changes applied to this Routine; local times in the schedule use the resulting time zone. */
fun Routine.with(spec: RoutineSpec): Routine {
    var c = this
    spec.name?.let { c = c.copy(name = it) }
    spec.prompt?.let { c = c.copy(prompt = it) }
    spec.timeZone?.let { c = c.copy(timeZone = it) }
    spec.activeHours?.let { c = c.copy(activeHours = activeHours(it)) }
    spec.approvals?.let { c = c.copy(approvals = it) }
    spec.enabled?.let { c = c.copy(enabled = it) }
    spec.session?.let { c = c.copy(sessionId = it.takeUnless { s -> s == NEW_SESSIONS }) }
    spec.trigger(zone(c.timeZone))?.let { c = c.copy(trigger = it) }
    return c
}

/** The schedule [spec] gives, if any. */
fun RoutineSpec.trigger(zone: TimeZone): Trigger? {
    require(listOfNotNull(at, every, cron).size <= 1) { "Give only one of at, every or cron" }
    at?.let { return Trigger.Once(instant(it, zone)) }
    every?.let { return Trigger.Every(minutes(it)) }
    cron?.let { return Trigger.Cron(it.trim()) }
    return null
}

const val NEW_SESSIONS = "new"

internal fun zone(id: String): TimeZone =
    try { TimeZone.of(id) } catch (e: IllegalTimeZoneException) { throw IllegalArgumentException("Unknown time zone '$id'") }

private fun instant(text: String, zone: TimeZone): Long = runCatching { Instant.parse(text) }
    .recoverCatching { LocalDateTime.parse(text).toInstant(zone) }
    .getOrElse { throw IllegalArgumentException("at must be an ISO date-time like 2026-05-01T09:00") }
    .toEpochMilliseconds()

private fun minutes(text: String): Long {
    val match = requireNotNull(Regex("""(\d+)\s*([mhd])""").matchEntire(text.trim().lowercase())) { "every must look like 30m, 2h or 1d" }
    val n = match.groupValues[1].toLong()
    return when (match.groupValues[2]) { "m" -> n; "h" -> n * 60; else -> n * 1440 }
}

private fun activeHours(text: String): ActiveHours? {
    if (text.isBlank() || text.equals("none", ignoreCase = true)) return null
    val (start, end) = text.split("-", "–").map { it.trim() }.takeIf { it.size == 2 } ?: error("active hours must look like 08:00-22:00")
    return ActiveHours(start, end)
}
