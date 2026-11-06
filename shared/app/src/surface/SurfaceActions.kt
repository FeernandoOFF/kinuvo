package kinuvo.app.surface

import dev.ynagai.a2ui.core.protocol.ActionMessage
import kinuvo.app.session.ChatItem
import kinuvo.protocol.SurfaceAction
import kotlinx.serialization.json.JsonObject

fun ActionMessage.surfaceAction(callId: String, dataModel: JsonObject): SurfaceAction =
    SurfaceAction(callId, surfaceId, name, sourceComponentId, context, dataModel)

fun List<ChatItem>.surfaceDataModels(callId: String): Map<String, JsonObject> =
    filterIsInstance<ChatItem.User>().mapNotNull { it.action }.filter { it.callId == callId }
        .associate { it.surfaceId to it.dataModel }

class SurfaceDrafts {
    private val invalid = mutableSetOf<String>()
    val canSubmit: Boolean get() = invalid.isEmpty()

    fun validate(componentId: String, valid: Boolean) {
        if (valid) invalid.remove(componentId) else invalid.add(componentId)
    }
}

class SurfaceSubmissions {
    private var applied = emptyMap<String, Pair<String, JsonObject>>()

    fun update(models: Map<String, Pair<String, JsonObject>>, apply: (String, JsonObject) -> Unit) {
        models.forEach { (id, model) -> if (applied[id] != model) apply(id, model.second) }
        applied = models
    }
}

fun openSurfaceUrl(url: String, open: (String) -> Unit) {
    if (url.startsWith("https://", ignoreCase = true) || url.startsWith("http://", ignoreCase = true)) open(url)
}

fun List<ChatItem>.surfaceSubmissions(callId: String): Map<String, Pair<String, JsonObject>> =
    filterIsInstance<ChatItem.User>().mapNotNull { item ->
        item.action?.takeIf { it.callId == callId }?.let { it.surfaceId to (item.id to it.dataModel) }
    }.toMap()
