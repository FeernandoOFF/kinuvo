package kinuvo.app.surface

import dev.ynagai.a2ui.core.protocol.CatalogDefinition
import dev.ynagai.a2ui.core.protocol.ComponentDefinition
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
    val extra = mapOf(
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
            if (key == "displayStyle" && value is JsonObject) JsonObject(value + ("enum" to JsonArray(listOf("checkbox", "chips", "dropdown").map(::JsonPrimitive))))
            else dropdown(value)
        })
        is JsonArray -> JsonArray(node.map(::dropdown))
        else -> node
    }
    val picker = basic.components.getValue("ChoicePicker")
    val components = basic.components + extra + ("ChoicePicker" to picker.copy(schema = dropdown(picker.schema).jsonObject))
    val defs = (basic.schemaKeywords["\$defs"] as? JsonObject).orEmpty().toMutableMap()
    defs["anyComponent"] = buildJsonObject {
        put("oneOf", JsonArray(components.keys.map { name -> buildJsonObject { put("\$ref", "#/components/$name") } }))
    }
    return basic.copy(catalogId = KINUVO_CATALOG_ID, components = components,
        schemaKeywords = basic.schemaKeywords + mapOf("\$id" to JsonPrimitive(KINUVO_CATALOG_ID), "\$defs" to JsonObject(defs)))
}
