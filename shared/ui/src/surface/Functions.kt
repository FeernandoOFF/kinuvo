package kinuvo.ui.surface

import androidx.compose.runtime.*
import dev.ynagai.a2ui.compose.A2uiComponentScope
import dev.ynagai.a2ui.core.function.EvaluationContext
import dev.ynagai.a2ui.core.protocol.A2uiJson
import dev.ynagai.a2ui.core.protocol.Action
import kinuvo.app.surface.SurfaceFunctions
import kotlinx.serialization.json.*

private fun A2uiComponentScope.localValue(name: String): JsonElement? {
    val raw = property(name) ?: return null
    val model = surface?.dataModel ?: return null
    val context = EvaluationContext(model).inScope(evaluationScope).withLocale(renderer.locale).withLimits(renderer.evaluationLimits)
    return runCatching { SurfaceFunctions(model, evaluationScope, context).evaluate(raw) }.getOrNull()
}

@Composable
internal fun A2uiComponentScope.surfaceString(name: String): String? =
    (localValue(name) as? JsonPrimitive)?.contentOrNull

@Composable
internal fun A2uiComponentScope.surfaceNumber(name: String): Double? =
    localValue(name).let { ((if (it is JsonArray) it.firstOrNull() else it) as? JsonPrimitive)?.doubleOrNull }

@Composable
internal fun A2uiComponentScope.surfaceBoolean(name: String): Boolean? =
    (localValue(name) as? JsonPrimitive)?.booleanOrNull

internal fun A2uiComponentScope.dispatchLocal(action: Action) {
    if (action is Action.Invoke && action.functionCall.call in setOf("append", "removeAt")) {
        surface?.let { SurfaceFunctions(it.dataModel, evaluationScope).edit(action.functionCall.call, action.functionCall.args.orEmpty()) }
            ?.let { (path, value) -> write(path, value) }
    } else if (action is Action.Event) {
        val raw = A2uiJson.strict.encodeToJsonElement(Action.serializer(), action).jsonObject
        val event = raw.getValue("event").jsonObject
        val functions = surface?.let { SurfaceFunctions(it.dataModel, evaluationScope) }
        val resolved = JsonObject(event.mapValues { (key, value) ->
            if (key == "context" && value is JsonObject) JsonObject(value.mapValues { (_, item) -> functions?.evaluate(item) ?: item })
            else if (key == "userMessage") functions?.evaluate(value) ?: value else value
        })
        dispatch(A2uiJson.strict.decodeFromJsonElement(Action.serializer(), buildJsonObject { put("event", resolved) }))
    } else dispatch(action)
}
