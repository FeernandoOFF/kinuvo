package kinuvo.app

import kinuvo.app.device.*

import kinuvo.protocol.ProtocolJson
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.*
import kotlin.test.*

class SleepSummaryTest {
    private fun sample(start: String, end: String, stage: String, source: String = "watch") = SleepSample("2026-10-04T${start}:00Z", "2026-10-04T${end}:00Z", stage, source, source)
    private fun summary(vararg samples: SleepSample) = sleepSummary(buildJsonObject { put("samples", ProtocolJson.encodeToJsonElement(ListSerializer(SleepSample.serializer()), samples.toList())) }, "Europe/Dublin")

    @Test
    fun countsOverlapsOnceAndExcludesAwakeAndInBed() {
        val result = summary(sample("01:00", "05:00", "in_bed"), sample("01:00", "04:00", "asleep"), sample("01:00", "02:00", "core"), sample("02:00", "03:00", "deep"), sample("03:00", "04:00", "rem"), sample("02:30", "03:00", "awake"))
        val session = result.getValue("sessions").jsonArray.single().jsonObject
        assertEquals(150.0, session.getValue("sleepMinutes").jsonPrimitive.double)
        assertEquals(30.0, session.getValue("stagesMinutes").jsonObject.getValue("deep").jsonPrimitive.double)
    }

    @Test
    fun usesOneSourceForTheSessionAndKeepsNapsSeparate() {
        val result = summary(sample("01:00", "04:00", "asleep"), sample("01:00", "03:00", "asleep", "phone"), sample("14:00", "15:00", "asleep"))
        val sessions = result.getValue("sessions").jsonArray
        assertEquals(2, sessions.size)
        assertEquals(180.0, sessions.first().jsonObject.getValue("sleepMinutes").jsonPrimitive.double)
        assertEquals("watch", sessions.first().jsonObject.getValue("sourceId").jsonPrimitive.content)
    }

    @Test
    fun aDayQueryKeepsTheWholePreviousNightAndExcludesOtherWakeDates() {
        val samples = listOf(
            SleepSample("2026-10-03T22:00:00Z", "2026-10-04T06:00:00Z", "asleep", "watch", "watch"),
            SleepSample("2026-10-04T22:00:00Z", "2026-10-05T06:00:00Z", "asleep", "watch", "watch"),
            SleepSample("2026-10-05T22:00:00Z", "2026-10-06T06:00:00Z", "asleep", "watch", "watch"),
        )
        val data = buildJsonObject { put("samples", ProtocolJson.encodeToJsonElement(ListSerializer(SleepSample.serializer()), samples)) }
        val result = sleepSummary(data, "Europe/Dublin", "2026-10-04T23:00:00Z", "2026-10-05T23:00:00Z")
        val session = result.getValue("sessions").jsonArray.single().jsonObject
        assertEquals(480.0, session.getValue("sleepMinutes").jsonPrimitive.double)
        assertEquals("2026-10-04T22:00:00Z", session.getValue("start").jsonPrimitive.content)
    }

    @Test
    fun inBedAndAwakeIntervalsDoNotMoveWakeDateOrBridgeSleepSessions() {
        val samples = listOf(
            SleepSample("2026-10-05T21:00:00Z", "2026-10-05T21:30:00Z", "asleep", "watch", "watch"),
            SleepSample("2026-10-05T21:00:00Z", "2026-10-06T08:00:00Z", "in_bed", "watch", "watch"),
            SleepSample("2026-10-06T05:00:00Z", "2026-10-06T06:00:00Z", "asleep", "watch", "watch"),
        )
        val data = buildJsonObject { put("samples", ProtocolJson.encodeToJsonElement(ListSerializer(SleepSample.serializer()), samples)) }
        val result = sleepSummary(data, "Europe/Dublin", "2026-10-04T23:00:00Z", "2026-10-05T23:00:00Z")
        val session = result.getValue("sessions").jsonArray.single().jsonObject
        assertEquals("2026-10-05", session.getValue("wakeDate").jsonPrimitive.content)
        assertEquals("2026-10-05T21:30:00Z", session.getValue("end").jsonPrimitive.content)
        assertEquals(30.0, session.getValue("sleepMinutes").jsonPrimitive.double)
        val interrupted = summary(sample("01:00", "08:00", "asleep"), sample("02:00", "06:00", "awake"))
        assertEquals(2, interrupted.getValue("sessions").jsonArray.size)
    }

    @Test
    fun overnightSleepUsesLocalWakeDateAndEmptyIsNotZeroSleep() {
        val result = summary(SleepSample("2026-10-04T22:00:00Z", "2026-10-05T06:00:00Z", "asleep", "watch", "watch"))
        assertEquals("2026-10-05", result.getValue("sessions").jsonArray.single().jsonObject.getValue("wakeDate").jsonPrimitive.content)
        assertTrue(summary().getValue("sessions").jsonArray.isEmpty())
        assertTrue("no read access" in summary().getValue("availability").jsonPrimitive.content)
    }
}
