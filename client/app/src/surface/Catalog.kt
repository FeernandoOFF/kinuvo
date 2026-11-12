package kinuvo.app.surface

import dev.ynagai.a2ui.core.protocol.CatalogDefinition
import dev.ynagai.a2ui.core.protocol.ComponentDefinition
import dev.ynagai.a2ui.core.protocol.A2uiJson
import dev.ynagai.a2ui.core.protocol.FunctionDefinition
import kotlinx.serialization.json.*

const val KINUVO_CATALOG_ID = "https://kinuvo.com/a2ui/v1/catalog.json"

fun surfaceCatalog(basic: CatalogDefinition): CatalogDefinition {
    fun property(component: String, name: String): JsonElement {
        fun find(node: JsonElement): JsonElement? = when (node) {
            is JsonObject -> (node["properties"] as? JsonObject)?.get(name)
                ?: node.values.firstNotNullOfOrNull(::find)
            is JsonArray -> node.firstNotNullOfOrNull(::find)
            else -> null
        }
        return requireNotNull(find(basic.components.getValue(component).schema))
    }
    val string = property("Text", "text")
    val child = property("Card", "child")
    val children = property("Column", "children")
    val action = property("Button", "action")
    fun component(name: String, properties: Map<String, JsonElement>, required: List<String>) = ComponentDefinition(buildJsonObject {
        put("type", "object")
        put("properties", JsonObject(mapOf("id" to buildJsonObject { put("type", "string") },
            "component" to buildJsonObject { put("const", name) },
            "weight" to buildJsonObject { put("type", "number"); put("exclusiveMinimum", 0) }) + properties))
        put("required", JsonArray((listOf("id", "component") + required).map(::JsonPrimitive)))
        put("additionalProperties", false)
    })
    fun enum(vararg values: String) = buildJsonObject { put("type", "string"); put("enum", JsonArray(values.map(::JsonPrimitive))) }
    val number = buildJsonObject { put("type", "number"); put("minimum", 0); put("maximum", 1000) }
    val dynamicNumber = property("Slider", "value")
    val dynamicBoolean = property("CheckBox", "value")
    val extra = mapOf(
        "Progress" to component("Progress", mapOf("value" to dynamicNumber, "max" to dynamicNumber), listOf("value")),
        "Stepper" to component("Stepper", mapOf("value" to dynamicNumber, "step" to dynamicNumber, "min" to dynamicNumber,
            "max" to dynamicNumber, "duration" to buildJsonObject { put("type", "boolean") }), listOf("value")),
        "Show" to component("Show", mapOf("when" to buildJsonObject { put("anyOf", JsonArray(listOf(dynamicBoolean, dynamicNumber))) }, "empty" to buildJsonObject { put("type", "boolean") }, "child" to child), listOf("when", "child")),
        "Badge" to component("Badge", mapOf("label" to string, "color" to enum("secondary", "success", "danger", "warning", "info", "discovery"),
            "variant" to enum("solid", "soft", "outline"), "size" to enum("sm", "md", "lg"),
            "pill" to buildJsonObject { put("type", "boolean") }), listOf("label")),
        "Spacer" to component("Spacer", mapOf("minSize" to number), emptyList()),
        "ListItem" to component("ListItem", mapOf("leading" to child, "title" to string, "subtitle" to string,
            "trailing" to child, "action" to action), listOf("title")),
        "Table" to component("Table", mapOf("children" to children), listOf("children")),
        "TableRow" to component("TableRow", mapOf("children" to children, "header" to buildJsonObject { put("type", "boolean") }), listOf("children")),
        "TableCell" to component("TableCell", mapOf("children" to children, "align" to enum("start", "center", "end")), listOf("children")),
        "Transition" to component("Transition", mapOf("child" to child), listOf("child")),
    )
    fun dropdown(node: JsonElement): JsonElement = when (node) {
        is JsonObject -> JsonObject(node.mapValues { (key, value) ->
            if (key == "displayStyle" && value is JsonObject) JsonObject(value + ("enum" to JsonArray(listOf("checkbox", "chips", "dropdown", "segmented").map(::JsonPrimitive))))
            else dropdown(value)
        })
        is JsonArray -> JsonArray(node.map(::dropdown))
        else -> node
    }
    val picker = basic.components.getValue("ChoicePicker")
    fun compactInput(node: JsonElement): JsonElement = when (node) {
        is JsonObject -> JsonObject(node.mapValues { (key, value) ->
            if (key == "required" && value is JsonArray) JsonArray(value.filter { it != JsonPrimitive("label") }) else compactInput(value)
        })
        is JsonArray -> JsonArray(node.map(::compactInput))
        else -> node
    }
    fun titles(node: JsonElement): JsonElement = when (node) {
        is JsonObject -> JsonObject(node.mapValues { (key, value) ->
            if (key == "variant" && value is JsonObject) JsonObject(value + ("enum" to JsonArray(listOf("caption", "body", "h1", "h2", "h3", "h4", "h5").map(::JsonPrimitive)))) else titles(value)
        })
        is JsonArray -> JsonArray(node.map(::titles))
        else -> node
    }
    val input = basic.components.getValue("TextField")
    val text = basic.components.getValue("Text")
    val components = basic.components + extra + ("TextField" to input.copy(schema = compactInput(input.schema).jsonObject)) +
        ("Text" to text.copy(schema = titles(text.schema).jsonObject)) + ("ChoicePicker" to picker.copy(schema = dropdown(picker.schema).jsonObject))
    val defs = (basic.schemaKeywords["\$defs"] as? JsonObject).orEmpty().toMutableMap()
    defs["anyComponent"] = buildJsonObject {
        put("oneOf", JsonArray(components.keys.map { name -> buildJsonObject { put("\$ref", "#/components/$name") } }))
    }
    fun function(name: String, result: String, description: String, arguments: List<String>, required: List<String> = arguments): FunctionDefinition =
        A2uiJson.strict.decodeFromJsonElement(FunctionDefinition.serializer(), buildJsonObject {
            put("type", "object"); put("description", description); put("returnType", result)
            if (result == "void") put("requiresUserActivation", true)
            putJsonObject("properties") {
                putJsonObject("call") { put("const", name) }
                putJsonObject("args") {
                    put("type", "object")
                    put("properties", JsonObject(arguments.associateWith { argument -> when (argument) {
                        "path" -> buildJsonObject { put("type", "string") }
                        "index", "minutes" -> dynamicNumber
                        "text" -> string
                        "where" -> buildJsonObject { put("type", "object") }
                        else -> buildJsonObject {}
                    } }))
                    put("required", JsonArray(required.map(::JsonPrimitive)))
                    put("additionalProperties", false)
                }
            }
            put("required", JsonArray(listOf("call", "args").map(::JsonPrimitive)))
        })
    val functions = basic.functions + mapOf(
        "copy" to function("copy", "void", "Copy text, or rows at path as a Markdown table, on button activation.", listOf("text", "path"), emptyList()),
        "append" to function("append", "void", "Append a literal value to the array at path; relative paths use the template item.", listOf("path", "value")),
        "removeAt" to function("removeAt", "void", "Remove index from array path. Omit both arguments inside a template to remove its current item.", listOf("path", "index"), emptyList()),
        "count" to function("count", "number", "Count values, optionally matching every field in where (choice arrays match a contained value).", listOf("values", "where"), listOf("values")),
        "multiply" to function("multiply", "number", "Multiply numeric values, including bound values.", listOf("values")),
        "sum" to function("sum", "number", "Sum numeric values or, with fields, the product of those numeric fields per row. Wildcard paths select nested values.", listOf("values", "fields"), listOf("values")),
        "formatDuration" to function("formatDuration", "string", "Format minutes as 1h 00m.", listOf("minutes")),
    )
    defs["anyFunction"] = buildJsonObject {
        put("oneOf", JsonArray(functions.keys.map { name -> buildJsonObject { put("\$ref", "#/functions/$name") } }))
    }
    return basic.copy(catalogId = KINUVO_CATALOG_ID, components = components, functions = functions,
        schemaKeywords = basic.schemaKeywords + mapOf("\$id" to JsonPrimitive(KINUVO_CATALOG_ID), "\$defs" to JsonObject(defs)))
}
