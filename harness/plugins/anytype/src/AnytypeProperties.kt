package kinuvo.harness.anytype

import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.*
import kotlin.time.Instant

/** Anytype property values as Note strings and back, by the property's format. */
internal object AnytypeProperties {
    /** Properties Anytype manages or computes, which Agents don't see or set. */
    val SYSTEM = setOf(
        "name", "type", "layout", "resolved_layout", "is_archived", "is_deleted", "is_hidden", "creator", "created_date",
        "last_modified_by", "last_modified_date", "last_opened_date", "lastOpenedDate", "last_message_date", "added_date",
        "origin", "source_object", "import_type", "backlinks", "links", "mentions", "created_in_context",
        "notification_subscribers", "featured_properties", "snippet", "size_in_bytes", "file_ext",
    )

    /** [value] as a string: dates ISO, checkboxes true/false, a select's option name, lists as JSON arrays; null when empty. */
    fun read(format: String?, value: JsonElement): String? = when {
        value is JsonNull -> null
        format == "select" -> (if (value is JsonArray) value.firstOrNull() else value)?.let(::name)
        format in LISTS || value is JsonArray -> (if (value is JsonArray) value else JsonArray(listOf(value)))
            .map { JsonPrimitive(name(it)) }.takeIf { it.isNotEmpty() }?.let { JsonArray(it).toString() }
        value is JsonPrimitive -> value.content
        else -> value.toString()
    }?.takeIf { it.isNotBlank() }

    /** [text] in [format]'s native shape; lists take a JSON array or one value, objects also `[[ref|label]]` links. */
    fun write(key: String, format: String, text: String): JsonElement = when (format) {
        "text", "url", "email", "phone", "emoji" -> JsonPrimitive(text)
        "number" -> runCatching { Json.parseToJsonElement(text.trim()) }.getOrNull().also {
            require(it is JsonPrimitive && !it.isString && it.doubleOrNull?.isFinite() == true) { "'$key' requires a finite number" }
        }!!
        "checkbox" -> JsonPrimitive(requireNotNull(text.trim().toBooleanStrictOrNull()) { "'$key' requires true or false" })
        "date" -> JsonPrimitive(text.trim()).also {
            require(runCatching { LocalDate.parse(text.trim()) }.isSuccess || runCatching { Instant.parse(text.trim()) }.isSuccess) { "'$key' requires an ISO date or timestamp" }
        }
        "select" -> JsonArray(listOf(JsonPrimitive(text.trim())))
        in LISTS -> {
            val items = if (text.trim().startsWith("[") && !text.trim().startsWith("[[")) {
                runCatching { Json.parseToJsonElement(text).jsonArray }.getOrNull()?.map {
                    require(it is JsonPrimitive && it.isString) { "'$key' requires a JSON array of strings" }
                    it.content
                } ?: error("'$key' requires a JSON array of strings")
            } else listOf(text)
            JsonArray(items.map { it.trim().let { v -> if (format == "multi_select") v else WIKI.matchEntire(v)?.groupValues?.get(1) ?: v } }
                .filter { it.isNotEmpty() }.distinct().map(::JsonPrimitive))
        }
        else -> error("Anytype property '$key' has unsupported format '$format'")
    }

    private fun name(value: JsonElement): String =
        if (value is JsonObject) (value["name"] ?: value["key"] ?: value["id"])?.jsonPrimitive?.content.orEmpty() else value.jsonPrimitive.content

    private val LISTS = setOf("multi_select", "objects", "files")
    private val WIKI = Regex("""\[\[([^\]|]+)(?:\|[^\]]*)?]]""")
}
