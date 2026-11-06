package kinuvo.app

import kinuvo.app.surface.*
import kotlin.test.*

class SurfaceValuesTest {
    @Test fun sliderClampsAndSnapsDivisions() {
        assertEquals(25.0, sliderValue(31.0, 0.0, 100.0, 4))
        assertEquals(100.0, sliderValue(500.0, 0.0, 100.0, 4))
        assertEquals(0.0, sliderValue(Double.NaN, 0.0, 100.0))
        assertEquals(0.0, sliderValue(40.0, 0.0, 100.0, 1))
        assertNull(sliderValue(1.0, 2.0, 1.0))
    }
    @Test fun validatesIsoDateAndTimeBounds() {
        assertEquals("2026-11-06", dateTimeValue("2026-11-06", true, false))
        assertEquals("12:30", dateTimeValue("12:30:00", false, true))
        assertEquals("2026-11-06T12:30", dateTimeValue("2026-11-06T12:30:00", true, true))
        assertEquals("", dateTimeValue("", true, false))
        assertNull(dateTimeValue("2026-02-30", true, false))
        assertEquals("2026-11-06T12:30", dateTimeValue("2026-11-06T12:30", true, true, min = "2026-11-06", max = "2026-11-06"))
        assertNull(dateTimeValue("2026-11-06T12:30", true, true, min = "13:00"))
        assertEquals("2026-11-06T12:30:00Z", dateTimeValue("2026-11-06T13:30:00+01:00", true, true, min = "2026-11-06T12:00:00Z"))
        assertEquals("12:30:00Z", dateTimeValue("13:30:00+01:00", false, true, max = "13:00:00Z"))
        assertNull(dateTimeValue("25:00", false, true))
        assertNull(dateTimeValue("2026-11-06", true, false, min = "2026-11-07"))
        assertNull(dateTimeValue("12:30", false, true, max = "12:00"))
    }
}
