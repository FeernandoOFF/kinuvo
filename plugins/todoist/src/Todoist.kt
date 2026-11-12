package kinuvo.plugins.todoist

import kinuvo.server.plugin.HarnessPlugin
import kinuvo.server.plugin.PluginHost
import kinuvo.server.plugin.ProviderTask
import kinuvo.server.plugin.TaskChange
import kinuvo.server.plugin.TaskApp
import kinuvo.server.plugin.TaskList
import kinuvo.server.plugin.TaskProvider
import kinuvo.protocol.plugin.PluginAccess
import kinuvo.protocol.plugin.PluginDefinition
import kinuvo.protocol.plugin.PluginHttpRequest
import kinuvo.protocol.settings.SettingsField
import kinuvo.protocol.settings.SettingsFieldType
import kinuvo.protocol.settings.SettingsSchema
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

class TodoistPlugin : HarnessPlugin {
    override val definition = PluginDefinition("kinuvo.todoist", "Todoist")
    override val taskProviders = listOf(TodoistProvider)
    override val manifest get() = super.manifest.copy(discoversTools = false)
}

object TodoistProvider : TaskProvider {
    const val DEFAULT_URL = "https://api.todoist.com"
    override val id = "todoist"
    override val name = "Todoist"
    override val settings = SettingsSchema(listOf(
        SettingsField("token", "API token", description = "From Todoist → Settings → Integrations → Developer.", required = true, sensitive = true),
        SettingsField("url", "API URL", SettingsFieldType.Url, "Only to reach Todoist through a proxy.", defaultValue = DEFAULT_URL),
    ))
    override val hostAccess = PluginAccess(network = setOf("url"), secrets = setOf("token"))
    override fun configured(options: Map<String, String>, host: PluginHost) = Todoist(host)
}

/** The user's Todoist through host-bound HTTP and API token; projects are lists. */
class Todoist(private val host: PluginHost, private val clock: Clock = Clock.System) : TaskApp {
    override suspend fun lists() = pages(listOf("projects")).map { TaskList(it.text("id"), it.text("name")) }

    override suspend fun tasks(listId: String, includeDone: Boolean): List<ProviderTask> {
        val open = pages(listOf("tasks"), mapOf("project_id" to listId)).map(::task)
        if (!includeDone) return open
        val now = clock.now()
        // Todoist lists done tasks by when they were completed, within a limited window.
        val done = pages(listOf("tasks", "completed", "by_completion_date"), mapOf(
            "project_id" to listId, "since" to (now - DONE_WINDOW).toString(), "until" to now.toString(),
        ), "items").map(::task)
        return open + done
    }

    override suspend fun task(id: String): ProviderTask? = call("GET", listOf("tasks", id), allowMissing = true)?.let { task(it.jsonObject) }

    override suspend fun create(listId: String, title: String, notes: String, due: LocalDate?): ProviderTask = task(call("POST", listOf("tasks"), buildJsonObject {
        put("content", title)
        put("description", notes)
        put("project_id", listId)
        due?.let { put("due_date", it.toString()) }
    })!!.jsonObject)

    override suspend fun update(taskId: String, change: TaskChange): ProviderTask {
        val fields = buildJsonObject {
            change.title?.let { put("content", it) }
            change.notes?.let { put("description", it) }
            if (change.clearDue) put("due_string", "no date") else change.due?.let { put("due_date", it.toString()) }
        }
        if (fields.isNotEmpty()) call("POST", listOf("tasks", taskId), fields)
        change.done?.let { call("POST", listOf("tasks", taskId, if (it) "close" else "reopen")) }
        return task(taskId) ?: error("No task '$taskId'")
    }

    private fun task(item: JsonObject) = ProviderTask(
        item.text("id"), item.text("project_id"), item.text("content"), item.text("description"),
        (item["due"] as? JsonObject)?.text("date")?.take(10)?.let(LocalDate::parse),
        item["checked"]?.jsonPrimitive?.booleanOrNull ?: item.text("completed_at").isNotEmpty(),
    )

    /** Every page of a cursor-paged listing; the items are under [key]. */
    private suspend fun pages(path: List<String>, query: Map<String, String> = emptyMap(), key: String = "results"): List<JsonObject> {
        val items = mutableListOf<JsonObject>()
        var cursor: String? = null
        do {
            val page = call("GET", path, query = query + listOfNotNull(cursor?.let { "cursor" to it }) + ("limit" to "200"))!!.jsonObject
            items += (page[key] as? JsonArray).orEmpty().map { it.jsonObject }
            cursor = page.text("next_cursor").ifEmpty { null }
        } while (cursor != null)
        return items
    }

    private suspend fun call(method: String, path: List<String>, body: JsonObject? = null, query: Map<String, String> = emptyMap(), allowMissing: Boolean = false): JsonElement? {
        val headers = mutableMapOf("Authorization" to "Bearer ${host.secret(TOKEN)}")
        if (body != null) headers["Content-Type"] = "application/json"
        val res = host.http(PluginHttpRequest(API, listOf("api", "v1") + path, method, headers, query, body?.toString()))
        if (allowMissing && res.status == 404) return null
        check(res.status in 200..299) {
            when (res.status) {
                401, 403 -> "Todoist refused the API token (HTTP ${res.status})"
                else -> "Todoist failed (HTTP ${res.status}): ${res.body.take(300)}"
            }
        }
        return res.body.takeIf { it.isNotBlank() }?.let(Json::parseToJsonElement)
    }

    private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

    private companion object {
        const val API = "url"
        const val TOKEN = "token"
        val DONE_WINDOW = 30.days
    }
}
