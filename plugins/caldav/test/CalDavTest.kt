package kinuvo.plugins.caldav

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kinuvo.server.plugin.PluginHosts
import kinuvo.server.plugin.instance
import kinuvo.server.plugin.ProviderTask
import kinuvo.server.plugin.TaskChange
import kinuvo.server.plugin.TaskList
import kinuvo.server.plugin.TaskApp
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.io.encoding.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CalDavTest {
    private class Request(val method: String, val path: String, val headers: Map<String, String>, val body: String)

    private val requests = mutableListOf<Request>()
    private var stored = TASK
    private var principal = "/remote.php/dav/principals/users/me/"
    private val http = HttpClient(MockEngine { req ->
        val body = (req.body as? TextContent)?.text.orEmpty()
        requests += Request(req.method.value, req.url.encodedPath, req.headers.entries().associate { it.key to it.value.first() }, body)
        if (req.headers[HttpHeaders.Authorization] != "Basic " + Base64.encode("me:app-pass".encodeToByteArray())) return@MockEngine respond("", HttpStatusCode.Unauthorized)
        val xml = headersOf(HttpHeaders.ContentType, "application/xml")
        when ("${req.method.value} ${req.url.encodedPath}") {
            "PROPFIND /remote.php/dav" -> respond(multistatus("""<d:response><d:href>/remote.php/dav/</d:href><d:propstat><d:prop>
                <d:current-user-principal><d:href>$principal</d:href></d:current-user-principal></d:prop>
                <d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"""), HttpStatusCode.MultiStatus, xml)
            "PROPFIND /remote.php/dav/principals/users/me/" -> respond(multistatus("""<d:response><d:href>/remote.php/dav/principals/users/me/</d:href>
                <d:propstat><d:prop><cal:calendar-home-set><d:href>/remote.php/dav/calendars/me/</d:href></cal:calendar-home-set></d:prop>
                <d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"""), HttpStatusCode.MultiStatus, xml)
            "PROPFIND /remote.php/dav/calendars/me/" -> respond(multistatus(
                calendar("/remote.php/dav/calendars/me/", "", "<d:collection/>", "") +
                calendar("/remote.php/dav/calendars/me/tasks", "Tasks &amp; chores", "<d:collection/><cal:calendar/>", """<cal:comp name="VTODO"/>""") +
                calendar("/remote.php/dav/calendars/me/personal/", "Personal", "<d:collection/><cal:calendar/>", """<cal:comp name="VEVENT"/>""")
            ), HttpStatusCode.MultiStatus, xml)
            "REPORT /remote.php/dav/calendars/me/tasks/" -> respond(multistatus("""<d:response><d:href>/remote.php/dav/calendars/me/tasks/a%20b.ics</d:href>
                <d:propstat><d:prop><d:getetag>"e1"</d:getetag><cal:calendar-data>${TASK.replace("&", "&amp;").replace("<", "&lt;")}</cal:calendar-data></d:prop>
                <d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"""), HttpStatusCode.MultiStatus, xml)
            "GET /remote.php/dav/calendars/me/tasks/a%20b.ics" -> respond(stored, HttpStatusCode.OK, headersOf(HttpHeaders.ETag, "\"e1\""))
            "GET /remote.php/dav/calendars/me/tasks/gone.ics" -> respond("", HttpStatusCode.NotFound)
            else -> if (req.method.value == "PUT") { stored = body; respond("", HttpStatusCode.Created) } else respond("", HttpStatusCode.NotFound)
        }
    })
    private val hosts = PluginHosts(http)

    @AfterTest fun close() { hosts.close(); http.close() }

    private fun caldav(password: String = "app-pass"): TaskApp {
        val options = mapOf("url" to "https://cloud.test/remote.php/dav", "username" to "me", "password" to password)
        val plugin = CalDavPlugin()
        val provider = plugin.taskProviders.single()
        provider.checkOptions(options)
        val grants = provider.grants(plugin.instance, options)
        return provider.configured(provider.effectiveOptions(options), hosts.bind(plugin.manifest, plugin.instance) { grants })
    }

    @Test
    fun findsTaskCalendarsFromThePrincipal() = runTest {
        assertEquals(listOf(TaskList("calendars/me/tasks/", "Tasks & chores")), caldav().lists())
        assertEquals("1", requests.last().headers["Depth"])
    }

    @Test
    fun readsOpenTasks() = runTest {
        val tasks = caldav().tasks("calendars/me/tasks/")
        assertEquals(listOf(ProviderTask("calendars/me/tasks/a b.ics", "calendars/me/tasks/", "Call the bank about the mortgage renewal before the end of the month",
            "Ask about rates, fees\nand the deadline", LocalDate(2026, 11, 5))), tasks)
        assertContains(requests.last().body, """<c:prop-filter name="COMPLETED"><c:is-not-defined/></c:prop-filter>""")
        assertNull(caldav().task("calendars/me/tasks/gone.ics"))
    }

    @Test
    fun updatesInPlaceKeepingWhatItDoesNotKnow() = runTest {
        val done = caldav().update("calendars/me/tasks/a b.ics", TaskChange(title = "Call the bank", clearDue = true, done = true))
        assertTrue(done.done)
        assertNull(done.due)
        val put = requests.last()
        assertEquals("PUT" to "\"e1\"", put.method to put.headers["If-Match"])
        assertContains(put.body, "SUMMARY:Call the bank\r\n")
        assertContains(put.body, "STATUS:COMPLETED\r\n")
        assertContains(put.body, "X-APPLE-SORT-ORDER:12\r\n")
        assertContains(put.body, "BEGIN:VALARM\r\nTRIGGER:-PT15M\r\n")
        assertFalse("DUE" in put.body)
        assertEquals("Ask about rates, fees\nand the deadline", ICalendar.parse(put.body).text("DESCRIPTION"))

        val reopened = caldav().update("calendars/me/tasks/a b.ics", TaskChange(done = false, due = LocalDate(2026, 12, 1)))
        assertFalse(reopened.done)
        assertContains(requests.last().body, "DUE;VALUE=DATE:20261201\r\n")
        assertFalse("COMPLETED:" in requests.last().body.replace("STATUS:", ""))
    }

    @Test
    fun createsNewTasksWithoutOverwriting() = runTest {
        val task = caldav().create("calendars/me/tasks/", "Water the plants", "Every pot, even the big one; twice", LocalDate(2026, 11, 7))
        val put = requests.last()
        assertEquals("*", put.headers["If-None-Match"])
        assertTrue(put.path.startsWith("/remote.php/dav/calendars/me/tasks/") && put.path.endsWith(".ics"))
        assertContains(put.body, "DESCRIPTION:Every pot\\, even the big one\\; twice\r\n")
        assertEquals(ProviderTask(task.id, "calendars/me/tasks/", "Water the plants", "Every pot, even the big one; twice", LocalDate(2026, 11, 7)), task)
        assertTrue(put.body.lines().all { it.encodeToByteArray().size <= 75 })
    }

    @Test
    fun rejectsAReferenceAsTheUrlAndHrefsOnAnotherHost() = runTest {
        val e = assertFailsWith<IllegalArgumentException> { CalDavProvider.checkOptions(mapOf("url" to "\${CALDAV_URL}", "username" to "me", "password" to "p")) }
        assertEquals("Write the Server URL itself, not a reference", e.message)
        val other = caldav()
        principal = "https://p01-caldav.example.com/remote.php/dav/principals/users/me/"
        assertContains(assertFailsWith<IllegalStateException> { other.lists() }.message!!, "another host, p01-caldav.example.com")
    }

    @Test
    fun editsTheMainTaskOfARecurringOneAndKeepsDueMatchingATimedStart() = runTest {
        stored = RECURRING
        val task = caldav().update("calendars/me/tasks/a b.ics", TaskChange(due = LocalDate(2026, 11, 20)))
        assertEquals("Weekly review", task.title)
        val ics = requests.last().body
        assertContains(ics, "DUE;TZID=Europe/Dublin:20261120T235959\r\n")
        assertContains(ics, "RECURRENCE-ID:20261108T090000Z\r\nSUMMARY:Weekly review (moved)\r\nDUE:20261108T090000Z\r\n")
        assertEquals(LocalDate(2026, 11, 20), task.due)

        val earlier = caldav().update("calendars/me/tasks/a b.ics", TaskChange(due = LocalDate(2026, 10, 1)))
        assertFalse("DTSTART" in requests.last().body.substringAfter("SUMMARY:Weekly review\r\n").substringBefore("END:VTODO"))
        assertEquals(LocalDate(2026, 10, 1), earlier.due)
    }

    @Test
    fun explainsARefusedPassword() = runTest {
        assertEquals("The CalDAV server refused the username or password (HTTP 401)",
            assertFailsWith<IllegalStateException> { caldav("wrong").lists() }.message)
    }

    private companion object {
        fun multistatus(body: String) = """<?xml version="1.0"?><d:multistatus xmlns:d="DAV:" xmlns:cal="urn:ietf:params:xml:ns:caldav">$body</d:multistatus>"""

        fun calendar(href: String, name: String, type: String, comps: String) = """<d:response><d:href>$href</d:href><d:propstat><d:prop>
            <d:resourcetype>$type</d:resourcetype><d:displayname>$name</d:displayname>
            ${if (comps.isEmpty()) "" else "<cal:supported-calendar-component-set>$comps</cal:supported-calendar-component-set>"}
            </d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"""

        val TASK = listOf(
            "BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//Apple//EN", "BEGIN:VTODO", "UID:a b", "DTSTAMP:20261101T100000Z",
            "SUMMARY:Call the bank about the mortgage renewal before the end of the mon", " th",
            "DESCRIPTION:Ask about rates\\, fees\\nand the deadline", "DUE;TZID=Europe/Dublin:20261105T090000", "X-APPLE-SORT-ORDER:12",
            "BEGIN:VALARM", "TRIGGER:-PT15M", "ACTION:DISPLAY", "SUMMARY:Alarm", "END:VALARM", "END:VTODO", "END:VCALENDAR",
        ).joinToString("\r\n", postfix = "\r\n")

        val RECURRING = listOf(
            "BEGIN:VCALENDAR", "VERSION:2.0",
            "BEGIN:VTODO", "UID:main", "RECURRENCE-ID:20261108T090000Z", "SUMMARY:Weekly review (moved)", "DUE:20261108T090000Z", "END:VTODO",
            "BEGIN:VTODO", "UID:main", "SUMMARY:Weekly review", "DTSTART;TZID=Europe/Dublin:20261101T090000", "DUE;TZID=Europe/Dublin:20261101T100000",
            "RRULE:FREQ=WEEKLY", "END:VTODO", "END:VCALENDAR",
        ).joinToString("\r\n", postfix = "\r\n")
    }
}
