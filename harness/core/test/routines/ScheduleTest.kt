package kinuvo.harness.core.routines

import kinuvo.protocol.ActiveHours
import kinuvo.protocol.Routine
import kinuvo.protocol.Trigger
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ScheduleTest {
    private val zone = TimeZone.of("Europe/Dublin")
    private fun at(text: String) = LocalDateTime.parse(text).toInstant(zone).toEpochMilliseconds()
    private fun routine(trigger: Trigger, hours: ActiveHours? = null, createdAt: Long = at("2026-01-01T00:00")) =
        Routine("r", "a", "R", "p", trigger, zone.id, activeHours = hours, createdAt = createdAt)

    @Test
    fun cronFindsNextMatch() {
        val weekdays = routine(Trigger.Cron("30 9 * * MON-FRI"))
        assertEquals(at("2026-10-02T09:30"), Schedule.next(weekdays, at("2026-10-01T09:30"))) // Thu → Fri
        assertEquals(at("2026-10-05T09:30"), Schedule.next(weekdays, at("2026-10-02T10:00"))) // Fri → Mon
        assertEquals(at("2026-11-01T00:00"), Schedule.next(routine(Trigger.Cron("0 0 1 * *")), at("2026-10-01T00:00")))
        assertEquals(at("2026-10-01T10:15"), Schedule.next(routine(Trigger.Cron("*/15 * * * *")), at("2026-10-01T10:05")))
    }

    @Test
    fun cronDayFieldsMatchEitherWhenBothSet() {
        // The 13th or any Friday.
        assertEquals(at("2026-10-09T00:00"), Schedule.next(routine(Trigger.Cron("0 0 13 * FRI")), at("2026-10-05T00:00")))
    }

    @Test
    fun steppedWildcardDayStillNeedsTheWeekday() {
        // Sat 3 Oct → Mon 5 Oct, as with a plain `*`.
        assertEquals(at("2026-10-05T09:00"), Schedule.next(routine(Trigger.Cron("0 9 */1 * MON-FRI")), at("2026-10-02T09:00")))
    }

    @Test
    fun badCronIsRejected() {
        assertFailsWith<IllegalArgumentException> { CronExpr("0 9 * *") }
        assertFailsWith<IllegalArgumentException> { CronExpr("61 9 * * *") }
        assertFailsWith<IllegalStateException> { CronExpr("0 9 * * FUNDAY") }
    }

    @Test
    fun everyIsAnchoredToCreation() {
        val r = routine(Trigger.Every(90), createdAt = at("2026-10-01T08:00"))
        assertEquals(at("2026-10-01T09:30"), Schedule.next(r, at("2026-10-01T08:00")))
        assertEquals(at("2026-10-01T11:00"), Schedule.next(r, at("2026-10-01T09:31")))
    }

    @Test
    fun activeHoursSkipSlotsOutsideTheWindow() {
        val r = routine(Trigger.Every(60), ActiveHours("08:00", "22:00"))
        assertEquals(at("2026-10-01T21:00"), Schedule.next(r, at("2026-10-01T20:30")))
        assertEquals(at("2026-10-02T08:00"), Schedule.next(r, at("2026-10-01T21:00")))
        val overnight = routine(Trigger.Every(60), ActiveHours("22:00", "02:00"))
        assertEquals(at("2026-10-01T22:00"), Schedule.next(overnight, at("2026-10-01T03:00")))
    }

    @Test
    fun onceFiresOnlyBeforeItsTime() {
        val r = routine(Trigger.Once(at("2026-10-01T09:00")))
        assertEquals(at("2026-10-01T09:00"), Schedule.next(r, at("2026-10-01T08:00")))
        assertNull(Schedule.next(r, at("2026-10-01T09:00")))
    }

    @Test
    fun latestSlotAndGrace() {
        val hourly = routine(Trigger.Every(60))
        assertEquals(at("2026-10-01T12:00"), Schedule.latest(hourly, at("2026-10-01T09:00"), at("2026-10-01T12:20")))
        assertEquals(30 * 60_000L, Schedule.grace(hourly, at("2026-10-01T12:00")))
        assertEquals(2 * 60 * 60_000L, Schedule.grace(routine(Trigger.Cron("0 9 * * *")), at("2026-10-01T09:00")))
        assertEquals(60_000L, Schedule.grace(routine(Trigger.Every(1)), at("2026-10-01T09:00")))
    }

    // Dublin 2026: clocks go forward 29 Mar 01:00 → 02:00 and back 25 Oct 02:00 → 01:00.
    @Test
    fun cronInSkippedHourRunsShiftedByTheGap() {
        val r = routine(Trigger.Cron("30 1 * * *"))
        assertEquals(at("2026-03-29T02:30"), Schedule.next(r, at("2026-03-28T01:30")))
        assertEquals(at("2026-03-30T01:30"), Schedule.next(r, at("2026-03-29T02:30")))
    }

    @Test
    fun cronInRepeatedHourRunsOnce() {
        val r = routine(Trigger.Cron("30 1 * * *"))
        val first = Schedule.next(r, at("2026-10-25T00:00"))!!
        assertEquals(at("2026-10-25T01:30"), first)
        assertEquals(at("2026-10-26T01:30"), Schedule.next(r, first))
    }

    @Test
    fun cronAroundChangesKeepsLocalTime() {
        val daily = routine(Trigger.Cron("0 9 * * *"))
        assertEquals(at("2026-03-29T09:00"), Schedule.next(daily, at("2026-03-28T09:00")))
        assertEquals(at("2026-10-25T09:00"), Schedule.next(daily, at("2026-10-24T09:00")))
        assertEquals(at("2026-03-29T02:00"), Schedule.next(routine(Trigger.Cron("0 * * * *")), at("2026-03-29T00:30")))
        assertEquals(at("2026-03-29T03:00"), Schedule.next(routine(Trigger.Cron("0 * * * *")), at("2026-03-29T02:00")))
    }

    @Test
    fun everyIgnoresClockChanges() {
        val r = routine(Trigger.Every(60), createdAt = at("2026-10-25T00:00"))
        assertEquals(at("2026-10-25T00:00") + 2 * 3_600_000, Schedule.next(r, at("2026-10-25T00:00") + 3_600_000))
    }

    @Test
    fun activeHoursUseLocalTimeAcrossChanges() {
        val r = routine(Trigger.Every(60), ActiveHours("08:00", "22:00"), createdAt = at("2026-03-28T00:00"))
        assertEquals(at("2026-03-29T08:00"), Schedule.next(r, at("2026-03-28T21:30")))
    }
}
