package kinuvo.harness.todoist

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kinuvo.harness.core.plugin.PluginHosts
import kinuvo.harness.core.plugin.instance
import kinuvo.harness.core.tasks.ProviderTask
import kinuvo.harness.core.tasks.TaskChange
import kinuvo.harness.core.tasks.TaskList
import kinuvo.harness.core.tasks.TaskApp
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class TodoistTest {
    private val requests = mutableListOf<String>()
    private var status = HttpStatusCode.OK
    private val http = HttpClient(MockEngine { req ->
        val body = (req.body as? TextContent)?.text
        requests += "${req.method.value} ${req.url.encodedPath}?${req.url.encodedQuery} ${req.headers[HttpHeaders.Authorization]}" + (body?.let { " $it" } ?: "")
        val json = when (req.url.encodedPath) {
            "/api/v1/projects" -> """{"results":[{"id":"p1","name":"Inbox"}],"next_cursor":null}"""
            "/api/v1/tasks" -> if (req.method.value == "POST") TASK
                else if (req.url.parameters["cursor"] == null) """{"results":[$TASK],"next_cursor":"c2"}"""
                else """{"results":[{"id":"t2","project_id":"p1","content":"Second","description":"","due":null,"checked":false}],"next_cursor":null}"""
            "/api/v1/tasks/completed/by_completion_date" -> """{"items":[{"id":"t3","project_id":"p1","content":"Old","checked":true}],"next_cursor":null}"""
            "/api/v1/tasks/t1" -> TASK
            "/api/v1/tasks/t1/close" -> ""
            else -> null
        }
        if (json == null) respond("", HttpStatusCode.NotFound)
        else respond(json, status, headersOf(HttpHeaders.ContentType, "application/json"))
    })
    private val hosts = PluginHosts(http) { _, value -> if (value == "\${op://Kinuvo/Todoist/token}") "secret-token" else value }

    @AfterTest fun close() { hosts.close(); http.close() }

    private fun todoist(options: Map<String, String> = mapOf("token" to "\${op://Kinuvo/Todoist/token}")): TaskApp {
        val plugin = TodoistPlugin()
        val provider = plugin.taskProviders.single()
        provider.checkOptions(options)
        val grants = provider.grants(plugin.instance, options)
        return provider.configured(provider.effectiveOptions(options), hosts.bind(plugin.manifest, plugin.instance) { grants })
    }

    @Test
    fun readsProjectsAndEveryPageOfTasks() = runTest {
        val todoist = todoist()
        assertEquals(listOf(TaskList("p1", "Inbox")), todoist.lists())
        assertEquals(listOf(ProviderTask("t1", "p1", "Buy milk", "2%", LocalDate(2026, 11, 5)), ProviderTask("t2", "p1", "Second")), todoist.tasks("p1"))
        assertEquals(listOf("t1", "t2", "t3"), todoist.tasks("p1", includeDone = true).map { it.id })
        assertEquals("GET /api/v1/projects?limit=200 Bearer secret-token", requests.first())
    }

    @Test
    fun createsAndUpdatesTasks() = runTest {
        val todoist = todoist()
        todoist.create("p1", "Buy milk", "2%", LocalDate(2026, 11, 5))
        assertEquals("""POST /api/v1/tasks? Bearer secret-token {"content":"Buy milk","description":"2%","project_id":"p1","due_date":"2026-11-05"}""", requests.last())
        requests.clear()
        todoist.update("t1", TaskChange(title = "Buy oat milk", clearDue = true, done = true))
        assertEquals(listOf(
            """POST /api/v1/tasks/t1? Bearer secret-token {"content":"Buy oat milk","due_string":"no date"}""",
            "POST /api/v1/tasks/t1/close? Bearer secret-token",
            "GET /api/v1/tasks/t1? Bearer secret-token",
        ), requests)
        assertNull(todoist.task("missing"))
    }

    @Test
    fun explainsARefusedToken() = runTest {
        status = HttpStatusCode.Unauthorized
        assertEquals("Todoist refused the API token (HTTP 401)", assertFailsWith<IllegalStateException> { todoist().lists() }.message)
        assertFailsWith<IllegalArgumentException> { todoist(emptyMap()) }
    }

    private companion object {
        const val TASK = """{"id":"t1","project_id":"p1","content":"Buy milk","description":"2%","due":{"date":"2026-11-05","is_recurring":false},"checked":false}"""
    }
}
