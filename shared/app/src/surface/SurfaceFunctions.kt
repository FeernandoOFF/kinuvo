package kinuvo.app.surface

import dev.ynagai.a2ui.core.function.EvaluationContext
import dev.ynagai.a2ui.core.function.evaluate
import dev.ynagai.a2ui.core.protocol.A2uiJson
import dev.ynagai.a2ui.core.protocol.BoundValue
import dev.ynagai.a2ui.core.surface.EvaluationScope
import dev.ynagai.a2ui.core.surface.JsonPointer
import dev.ynagai.a2ui.core.surface.resolve
import kotlinx.serialization.json.*

class SurfaceFunctions(val model: JsonObject, val scope: EvaluationScope,
    private val context: EvaluationContext = EvaluationContext(model).inScope(scope)) {
    fun path(raw: String): JsonPointer = scope.base.resolve(JsonPointer.parse(raw))

    fun evaluate(raw: JsonElement): JsonElement {
        if (raw is JsonArray) return JsonArray(raw.map(::evaluate))
        if (raw !is JsonObject) return raw
        val call = (raw["call"] as? JsonPrimitive)?.content
        if (call == null && "path" !in raw) return JsonObject(raw.mapValues { (_, value) -> evaluate(value) })
        val args = (raw["args"] as? JsonObject).orEmpty()
        fun arg(name: String) = args[name]?.let(::evaluate) ?: JsonNull
        fun values(): List<JsonElement> {
            val value = arg("values")
            return if (value is JsonArray) value else emptyList()
        }
        return when (call) {
            "formatString" -> {
                val template = (arg("value") as? JsonPrimitive)?.content.orEmpty()
                if (Regex("\\b(count|sum|multiply|formatDuration)\\s*\\(").containsMatchIn(template)) JsonPrimitive(interpolate(template))
                else context.evaluate(A2uiJson.strict.decodeFromJsonElement(BoundValue.serializer(), buildJsonObject {
                    put("call", "formatString"); putJsonObject("args") { put("value", template) }
                }))
            }
            "multiply" -> JsonPrimitive(values().fold(1.0) { product, value -> product * ((value as? JsonPrimitive)?.doubleOrNull ?: 0.0) }.takeIf { it.isFinite() } ?: 0.0)
            "count" -> JsonPrimitive(countSurfaceValues(JsonArray(values()), args["where"] as? JsonObject))
            "sum" -> {
                val fields = (args["fields"] as? JsonArray)?.map { it.jsonPrimitive.content }
                JsonPrimitive(sumSurfaceValues(if (fields == null) values() else values().map { row ->
                    JsonPrimitive(fields.fold(1.0) { product, field ->
                        product * (((row as? JsonObject)?.get(field) as? JsonPrimitive)?.doubleOrNull ?: 0.0)
                    }.takeIf { it.isFinite() } ?: 0.0)
                }))
            }
            "formatDuration" -> JsonPrimitive(formatSurfaceDuration((arg("minutes") as? JsonPrimitive)?.doubleOrNull ?: 0.0))
            else -> {
                val pointer = (raw["path"] as? JsonPrimitive)?.content
                if (pointer != null && '*' in pointer) JsonArray(surfaceValues(model, path(pointer).tokens))
                else {
                    val resolved = if (call != null) JsonObject(raw + ("args" to JsonObject(args.mapValues { (_, value) ->
                        evaluate(value)
                    }))) else raw
                    context.evaluate(A2uiJson.strict.decodeFromJsonElement(BoundValue.serializer(), resolved))
                }
            }
        }
    }

    fun edit(call: String, args: Map<String, JsonElement>): Pair<JsonPointer, JsonElement>? {
        val raw = (args["path"] as? JsonPrimitive)?.content
        val target = if (raw != null) path(raw) else if (call == "removeAt" && scope.index != null)
            JsonPointer.of(*scope.base.tokens.dropLast(1).toTypedArray()) else return null
        val values = model.resolve(target) as? JsonArray ?: return null
        return target to when (call) {
            "append" -> appendSurfaceValue(values, args["value"] ?: JsonNull)
            "removeAt" -> {
                val index = if ("index" in args) (evaluate(args.getValue("index")) as? JsonPrimitive)?.intOrNull
                    else scope.index.takeIf { raw == null }
                removeSurfaceValue(values, index ?: return null)
            }
            else -> return null
        }
    }
}
