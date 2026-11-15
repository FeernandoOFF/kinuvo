package kinuvo.plugins.apple

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Runs against the real EventKit when `KINUVO_LIVE_APPLE=1`; the process needs an app bundle that holds the permissions. */
class LiveAppleTest {
    @Test
    fun aBareProcessCannotReachThem() = runTest {
        if (System.getenv("KINUVO_LIVE_APPLE") == "1") return@runTest
        val error = assertFailsWith<AppleException> { Apple(loadAppleSystem()).call("reminders.lists") }
        assertEquals("unavailable", error.code)
        assertEquals(UNAVAILABLE, error.message)
    }

    @Test
    fun listsRemindersAndCalendars() = runTest {
        if (System.getenv("KINUVO_LIVE_APPLE") != "1") return@runTest
        val apple = Apple(loadAppleSystem())
        assertTrue(AppleReminders(apple).lists().all { it.id.isNotBlank() && it.name.isNotBlank() })
        assertTrue(apple.get<List<AppleList>>("calendar.list").all { it.id.isNotBlank() })
        apple.get<List<AppleEvent>>("calendar.events")
    }
}
