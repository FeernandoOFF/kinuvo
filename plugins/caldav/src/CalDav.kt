package kinuvo.plugins.caldav

import kinuvo.server.plugin.SecretReferences
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.Url
import io.ktor.http.decodeURLPart
import kinuvo.server.plugin.HarnessPlugin
import kinuvo.server.plugin.PluginHost
import kinuvo.server.plugin.httpServiceUrl
import kinuvo.server.plugin.ProviderTask
import kinuvo.server.plugin.TaskChange
import kinuvo.server.plugin.TaskApp
import kinuvo.server.plugin.TaskList
import kinuvo.server.plugin.TaskProvider
import kinuvo.protocol.PluginAccess
import kinuvo.protocol.PluginDefinition
import kinuvo.protocol.PluginHttpRequest
import kinuvo.protocol.PluginHttpResponse
import kinuvo.protocol.SettingsField
import kinuvo.protocol.SettingsFieldType
import kinuvo.protocol.SettingsSchema
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.io.encoding.Base64
import kotlin.time.Clock
import kotlin.uuid.Uuid

class CalDavPlugin : HarnessPlugin {
    override val definition = PluginDefinition("kinuvo.caldav", "CalDAV")
    override val taskProviders = listOf(CalDavProvider)
    override val manifest get() = super.manifest.copy(discoversTools = false)
}

object CalDavProvider : TaskProvider {
    override val id = "caldav"
    override val name = "CalDAV"
    override val settings = SettingsSchema(listOf(
        SettingsField(URL, "Server URL", SettingsFieldType.Url, "Your CalDAV address, e.g. https://cloud.example.com/remote.php/dav for Nextcloud " +
            "or https://caldav.fastmail.com/dav/ for Fastmail. It can't be a reference.", required = true),
        SettingsField(USERNAME, "Username", required = true),
        SettingsField(PASSWORD, "Password", description = "Use an app password where your server offers one.", required = true, sensitive = true),
    ))
    override val hostAccess = PluginAccess(network = setOf(URL), secrets = setOf(USERNAME, PASSWORD))

    /** The URL is needed as written: the server's paths are matched against it. */
    override fun checkOptions(options: Map<String, String>) {
        super.checkOptions(options)
        require(!SecretReferences.hasReferences(options[URL].orEmpty())) { "Write the Server URL itself, not a reference" }
    }

    override fun configured(options: Map<String, String>, host: PluginHost) = CalDav(host, Url(httpServiceUrl(options.getValue(URL))))
}

/**
 * Task lists on a CalDAV server (Nextcloud, Fastmail, Radicale, Baïkal…) at [base]: calendars that hold VTODOs are lists.
 * Ids are paths under [base]: a list's is its calendar's (ending in `/`), a task's is its `.ics` resource's.
 */
class CalDav(
    private val host: PluginHost,
    private val base: Url,
    private val clock: Clock = Clock.System,
    private val zone: TimeZone = TimeZone.currentSystemDefault(),
) : TaskApp {
    private val basePath = base.encodedPath.decodeURLPart().trimEnd('/')

    override suspend fun lists(): List<TaskList> {
        val home = home()
        return propfind(home, 1, "<d:resourcetype/><d:displayname/><c:supported-calendar-component-set/>").mapNotNull { (path, props) ->
            val calendar = props["resourcetype"]?.child("calendar") != null
            val components = props["supported-calendar-component-set"]?.all("comp")?.map { it.attributes["name"]?.uppercase() }
            if (!calendar || components?.isNotEmpty() == true && "VTODO" !in components) return@mapNotNull null
            TaskList(path.trimEnd('/') + "/", props["displayname"]?.text?.trim()?.ifEmpty { null } ?: path.trimEnd('/').substringAfterLast('/'))
        }
    }

    override suspend fun tasks(listId: String, includeDone: Boolean): List<ProviderTask> {
        val open = if (includeDone) "" else """<c:prop-filter name="COMPLETED"><c:is-not-defined/></c:prop-filter>"""
        val res = call("REPORT", listId, """<?xml version="1.0" encoding="utf-8"?><c:calendar-query xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">""" +
            """<d:prop><d:getetag/><c:calendar-data/></d:prop><c:filter><c:comp-filter name="VCALENDAR"><c:comp-filter name="VTODO">$open""" +
            """</c:comp-filter></c:comp-filter></c:filter></c:calendar-query>""", depth = 1)
        return responses(res).mapNotNull { (path, props) ->
            val data = props["calendar-data"]?.text?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            runCatching { task(path, ICalendar.parse(data)) }.onFailure { log.warn { "Skipped unreadable task $path: ${it.message}" } }.getOrNull()
        }.filter { includeDone || !it.done }
    }

    override suspend fun task(id: String): ProviderTask? = read(id)?.let { task(id, it.first) }

    override suspend fun create(listId: String, title: String, notes: String, due: LocalDate?): ProviderTask {
        val uid = Uuid.random().toString()
        val id = listId.trimEnd('/') + "/$uid.ics"
        val ics = ICalendar.todo(uid, title, notes, due, clock.now())
        val res = call("PUT", id, ics.toString(), mapOf("If-None-Match" to "*", "Content-Type" to CALENDAR))
        check(res.status in 200..299) { failure("Saving the task", res) }
        return task(id, ics)
    }

    override suspend fun update(taskId: String, change: TaskChange): ProviderTask {
        val (ics, etag) = read(taskId) ?: error("No task '$taskId'")
        val now = clock.now()
        change.title?.let { ics.setText("SUMMARY", it) }
        change.notes?.let { ics.setText("DESCRIPTION", it) }
        if (change.clearDue) ics.setDue(null) else change.due?.let { ics.setDue(it) }
        change.done?.let { ics.setDone(it, now) }
        ics.touch(now)
        val res = call("PUT", taskId, ics.toString(), listOfNotNull("Content-Type" to CALENDAR, etag?.let { "If-Match" to it }).toMap())
        check(res.status != 412) { "The task changed in your task app meanwhile; try again" }
        check(res.status in 200..299) { failure("Saving the task", res) }
        return task(taskId, ics)
    }

    private fun task(id: String, ics: ICalendar) = ProviderTask(
        id, id.substringBeforeLast('/') + "/", ics.text("SUMMARY"), ics.text("DESCRIPTION"), ics.due(zone), ics.done,
    )

    private suspend fun read(id: String): Pair<ICalendar, String?>? {
        val res = call("GET", id)
        if (res.status == 404) return null
        check(res.status in 200..299) { failure("Reading the task", res) }
        return ICalendar.parse(res.body) to res.header("ETag")
    }

    /** The calendar home: from the user's principal, else the server URL itself, e.g. when it's already the home. */
    private suspend fun home(): String {
        val principal = propfind("", 0, "<d:current-user-principal/>").firstOrNull()?.second?.get("current-user-principal")
            ?.child("href")?.text?.let(::relative) ?: ""
        return propfind(principal, 0, "<c:calendar-home-set/>").firstOrNull()?.second?.get("calendar-home-set")
            ?.child("href")?.text?.let(::relative) ?: principal
    }

    /** Each resource's path with its found properties by local name. */
    private suspend fun propfind(path: String, depth: Int, props: String) = responses(call("PROPFIND", path,
        """<?xml version="1.0" encoding="utf-8"?><d:propfind xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav"><d:prop>$props</d:prop></d:propfind>""", depth = depth))

    private fun responses(res: PluginHttpResponse): List<Pair<String, Map<String, Xml>>> {
        check(res.status == 207) { failure("Listing", res) }
        return Xml.parse(res.body).all("response").mapNotNull { response ->
            val path = response.child("href")?.text?.trim()?.let(::relative) ?: return@mapNotNull null
            val props = response.all("propstat").filter { it.child("status")?.text?.contains(" 200") != false }
                .flatMap { it.child("prop")?.children.orEmpty() }.associateBy { it.name }
            path to props
        }
    }

    /** [href] as a path under [base]. */
    private fun relative(href: String): String {
        val url = if ("://" in href) Url(href) else null
        check(url == null || url.host == base.host && url.port == base.port) { "The server pointed to another host, ${url?.host}; set the Server URL to your account's address there" }
        val path = (url?.encodedPath ?: href).decodeURLPart()
        if (basePath.isEmpty()) return path.removePrefix("/")
        check(path == basePath || path.startsWith("$basePath/")) { "The server pointed outside its URL, to $path; set the Server URL to where your CalDAV account is" }
        return path.removePrefix(basePath).removePrefix("/")
    }

    private suspend fun call(method: String, path: String, body: String? = null, headers: Map<String, String> = emptyMap(), depth: Int? = null): PluginHttpResponse {
        val auth = Base64.encode("${host.secret(USERNAME)}:${host.secret(PASSWORD)}".encodeToByteArray())
        val all = headers + ("Authorization" to "Basic $auth") + listOfNotNull(depth?.let { "Depth" to "$it" }) +
            listOfNotNull(body?.takeIf { "Content-Type" !in headers }?.let { "Content-Type" to "application/xml; charset=utf-8" })
        val res = host.http(PluginHttpRequest(URL, path.split('/').filterIndexed { i, s -> s.isNotEmpty() || i > 0 }, method, all, body = body))
        check(res.status != 401 && res.status != 403) { "The CalDAV server refused the username or password (HTTP ${res.status})" }
        return res
    }

    private fun PluginHttpResponse.header(name: String) = headers.entries.firstOrNull { it.key.equals(name, true) }?.value?.firstOrNull()

    private fun failure(what: String, res: PluginHttpResponse) = "$what failed on the CalDAV server (HTTP ${res.status}): ${res.body.take(300)}"

    private companion object {
        const val CALENDAR = "text/calendar; charset=utf-8"
        val log = KotlinLogging.logger("kinuvo.caldav")
    }
}

private const val URL = "url"
private const val USERNAME = "username"
private const val PASSWORD = "password"
