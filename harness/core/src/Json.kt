package kinuvo.harness.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

fun JsonElement.deterministic(): JsonElement = when (this) {
    is JsonObject -> JsonObject(entries.sortedBy { it.key }.associate { it.key to it.value.deterministic() })
    is JsonArray -> JsonArray(map { it.deterministic() })
    else -> this
}
