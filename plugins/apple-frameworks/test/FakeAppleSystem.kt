package kinuvo.plugins.apple

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Stands in for the Swift core: a few lists, reminders and events in memory, and every call it received. */
class FakeAppleSystem(var access: String = "fullAccess") : AppleSystem {
    val calls = mutableListOf<Pair<String, JsonObject>>()
    private val reminders = mutableListOf(
        reminder("r1", "Errands", "Buy milk", due = "2026-11-12"),
        reminder("r2", "Errands", "Call mum", notes = "About the trip", due = "2026-11-13T09:30"),
        reminder("r3", "Work", "File report", done = true),
    )
    private val events = mutableListOf(
        event("e1", "Dentist", "2026-11-12T10:00", "2026-11-12T11:00", location = "Main St"),
        event("e2", "Holiday", "2026-11-14", "2026-11-16", allDay = true),
    )

    override fun call(method: String, params: String): String {
        val p = Json.parseToJsonElement(params).jsonObject
        calls += method to p
        return if (method != "authorize" && method != "status" && access != "fullAccess") error("denied", "Reminders access is $access.") else runCatching { result(method, p) }.fold({ ok(it) }, {
            error(if (it is NoSuchElementException) "notFound" else "failed", it.message.orEmpty())
        })
    }

    private fun result(method: String, p: JsonObject): JsonElement = when (method) {
        "status" -> buildJsonObject { put("reminders", access); put("calendar", access) }
        "authorize" -> buildJsonObject { put("status", access); if (access != "fullAccess") put("message", "Enable Kinuvo in System Settings.") }
        "reminders.lists" -> JsonArray(listOf("Errands", "Work").map { buildJsonObject { put("id", it.lowercase()); put("title", it) } })
        "reminders.list" -> JsonArray(reminders.filter { r -> p.text("list")?.let { r.text("list_id") == it.lowercase() } != false && (p.bool("include_done") || !r.bool("done")) })
        "reminders.get" -> reminders.first { it.text("id") == p.text("id") }
        "reminders.create" -> reminder("r${reminders.size + 1}", p.text("list")?.replaceFirstChar { it.uppercase() } ?: "Errands", p.text("title")!!, p.text("notes").orEmpty(), p.text("due")).also { reminders += it }
        "reminders.update" -> {
            val old = reminders.first { it.text("id") == p.text("id") }
            val due = if (p.bool("clear_due") || p.text("due") == "none") null else p.text("due") ?: old.text("due")
            reminder(old.text("id")!!, old.text("list")!!, p.text("title") ?: old.text("title")!!, p.text("notes") ?: old.text("notes")!!, due, p["done"]?.jsonPrimitive?.boolean ?: old.bool("done"))
                .also { reminders[reminders.indexOf(old)] = it }
        }
        "calendar.list" -> JsonArray(listOf(buildJsonObject { put("id", "home"); put("title", "Home") }, buildJsonObject { put("id", "holidays"); put("title", "Holidays"); put("writable", false) }))
        "calendar.events" -> JsonArray(events.filter { e -> p.text("query")?.let { it.lowercase() in e.text("title")!!.lowercase() } != false })
        "calendar.create" -> event("e${events.size + 1}", p.text("title")!!, p.text("start")!!, p.text("end") ?: p.text("start")!!, allDay = p.bool("all_day")).also { events += it }
        "calendar.update" -> {
            val old = events.first { it.text("id") == p.text("id") }
            event(old.text("id")!!, p.text("title") ?: old.text("title")!!, old.text("start")!!, old.text("end")!!).also { events[events.indexOf(old)] = it }
        }
        "calendar.delete" -> events.first { it.text("id") == p.text("id") }.also { events.remove(it) }
        else -> throw IllegalArgumentException("Unknown method '$method'")
    }

    private fun ok(result: JsonElement) = buildJsonObject { put("result", result) }.toString()
    private fun error(code: String, message: String) = """{"error":{"code":"$code","message":"$message"}}"""
    private fun JsonObject.text(key: String) = get(key)?.jsonPrimitive?.contentOrNull
    private fun JsonObject.bool(key: String) = get(key)?.jsonPrimitive?.boolean == true

    private fun reminder(id: String, list: String, title: String, notes: String = "", due: String? = null, done: Boolean = false) = buildJsonObject {
        put("id", id); put("list_id", list.lowercase()); put("list", list); put("title", title); put("notes", notes); put("done", done)
        due?.let { put("due", it) }
    }

    private fun event(id: String, title: String, start: String, end: String, allDay: Boolean = false, location: String = "") = buildJsonObject {
        put("id", id); put("calendar_id", "home"); put("calendar", "Home"); put("title", title); put("start", start); put("end", end)
        put("all_day", allDay); put("location", location); put("notes", ""); put("recurring", false)
    }
}
