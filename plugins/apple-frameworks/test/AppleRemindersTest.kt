package kinuvo.plugins.apple

import kinuvo.server.plugin.TaskChange
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppleRemindersTest {
    private val system = FakeAppleSystem()
    private val reminders = AppleReminders(Apple(system))

    @Test
    fun mapsListsAndTasks() = runTest {
        assertEquals(listOf("Errands", "Work"), reminders.lists().map { it.name })
        val open = reminders.tasks("errands")
        assertEquals(listOf("Buy milk", "Call mum"), open.map { it.title })
        assertEquals(LocalDate(2026, 11, 13), open.last().due)
        assertEquals("About the trip", open.last().notes)
        assertEquals(listOf("File report"), reminders.tasks("work", includeDone = true).map { it.title })
        assertEquals("work", system.calls.last { it.first == "reminders.list" }.second["list"]!!.jsonPrimitive.content)
    }

    @Test
    fun findsATaskOrNull() = runTest {
        assertEquals("Buy milk", reminders.task("r1")!!.title)
        assertNull(reminders.task("gone"))
    }

    @Test
    fun createsAndUpdates() = runTest {
        val created = reminders.create("work", "Pay rent", "Before Friday", LocalDate(2026, 11, 20))
        assertEquals("work", created.listId)
        assertEquals(LocalDate(2026, 11, 20), created.due)
        val sent = system.calls.last { it.first == "reminders.create" }.second
        assertEquals("2026-11-20", sent["due"]!!.jsonPrimitive.content)

        val done = reminders.update("r1", TaskChange(done = true, clearDue = true, notes = "Oat"))
        assertTrue(done.done)
        assertNull(done.due)
        assertEquals("Oat", done.notes)
        assertFalse(reminders.update("r1", TaskChange(done = false)).done)
    }

    @Test
    fun authorizeReportsWhatToDoWhenDenied() = runTest {
        reminders.authorize()
        system.access = "denied"
        assertEquals("Enable Kinuvo in System Settings.", assertFailsWith<IllegalStateException> { reminders.authorize() }.message)
        assertEquals("denied", assertFailsWith<AppleException> { reminders.lists() }.code)
    }

    @Test
    fun exposesPermissionState() = runTest {
        assertEquals(mapOf("reminders" to "fullAccess", "calendar" to "fullAccess"), Apple(system).status())
        assertEquals("fullAccess", Apple(system).authorize("calendar").status)
        system.access = "denied"
        assertEquals("denied", Apple(system).authorize("calendar").status)
    }

    @Test
    fun anUnavailableSystemSaysSo() = runTest {
        val error = assertFailsWith<AppleException> { AppleReminders(Apple(UnavailableAppleSystem())).lists() }
        assertEquals("unavailable", error.code)
        assertEquals(UNAVAILABLE, error.message)
        assertTrue("Kinuvo desktop app" in UNAVAILABLE && "iPhone app" in UNAVAILABLE)
    }
}
