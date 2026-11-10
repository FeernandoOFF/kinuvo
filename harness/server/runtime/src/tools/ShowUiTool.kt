package kinuvo.harness.core.tools

import kinuvo.server.plugin.toolSpec
import kinuvo.server.plugin.ParameterType
import kinuvo.server.plugin.ToolParameter
import kinuvo.protocol.ToolNames
import kinuvo.server.plugin.CoreTool
import dev.ynagai.a2ui.core.protocol.A2uiJson
import dev.ynagai.a2ui.core.protocol.AgentToRendererMessage
import dev.ynagai.a2ui.core.protocol.CatalogDefinition
import dev.ynagai.a2ui.core.protocol.CreateSurfaceMessage
import dev.ynagai.a2ui.core.protocol.DeleteSurfaceMessage
import dev.ynagai.a2ui.core.protocol.UpdateComponentsMessage
import dev.ynagai.a2ui.core.protocol.UpdateDataModelMessage
import dev.ynagai.a2ui.core.surface.MessageProcessor
import dev.ynagai.a2ui.core.surface.RendererState
import dev.ynagai.a2ui.core.validation.CatalogValidator
import kinuvo.protocol.ProtocolJson
import kinuvo.protocol.UiCatalog
import kinuvo.protocol.uiMessages
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*

/** Shows the user an A2UI surface in the chat, checked against the catalogs their Devices render. */
class ShowUiTool(catalogs: List<UiCatalog>) : CoreTool {
    private val validator = CatalogValidator.of(catalogs.map {
        A2uiJson.lenient.decodeFromJsonElement(CatalogDefinition.serializer(), ProtocolJson.encodeToJsonElement(UiCatalog.serializer(), it))
    })

    override val descriptor = toolSpec(
        name = ToolNames.SHOW_UI,
        description = "Show native interactive Surfaces using A2UI v1.0. Use the advertised catalog schemas. " +
            "Start with createSurface(surfaceId,catalogId,components,dataModel); components are flat, with id root and child/children references. " +
            "Bind inputs with {\"path\":\"/pointer\"}; templates use children={\"componentId\":\"row\",\"path\":\"/rows\"}. " +
            "Kinuvo includes Badge, Spacer, ListItem, Table/TableRow/TableCell, Transition, Stepper, Progress and Show; " +
            "ChoicePicker supports dropdown and segmented. Columns fill width; Row centres children; Spacer with weight:1 pushes trailing actions. " +
            "Use h1-h5/caption Text, borderless icon actions, and primary submit buttons. " +
            "Client functions append(path,value), removeAt(path?,index?), count(values,where?), sum(values,fields?), multiply(values), " +
            "formatDuration(minutes), copy(text|path) edit and compute locally. sum fields multiplies those numeric fields per row before summing. " +
            "Wildcard paths select nested arrays. formatString interpolation supports nested client functions, e.g. " +
            "{\"call\":\"formatString\",\"args\":{\"value\":\"${'$'}{count(/rows)} tasks\"}}. " +
            "Button.action.functionCall runs locally; omit removeAt arguments inside a template to delete its item. " +
            "Button.action.event={name,userMessage?,context?} submits resolved values to the Agent. " +
            "Checks use {condition:{call,args},message}. Each call creates independent Surfaces.\n\nCatalogs:\n" +
            catalogs.joinToString("\n\n") { catalogSummary(it) },
        requiredParameters = listOf(
            ToolParameter("messages", "A2UI messages, e.g. [{\"createSurface\": {...}}]",
                ParameterType.List(ParameterType.Object(emptyList(), additionalProperties = true))),
        ),
    )

    override suspend fun execute(args: JsonObject): String {
        val messages = uiMessages(args)
        require(messages.isNotEmpty()) { "Give at least one message" }
        val catalogs = mutableMapOf<String, String?>()
        val decoded = messages.mapIndexed { i, raw ->
            val message = try {
                A2uiJson.strict.decodeFromJsonElement(AgentToRendererMessage.serializer(), raw)
            } catch (error: SerializationException) {
                val operation = raw.keys.firstOrNull { it != "version" } ?: "message"
                val detail = error.message.orEmpty()
                val unknownKey = Regex("unknown key '([^']+)'").find(detail)?.groupValues?.get(1)
                val reason = if (unknownKey != null) "$operation has unknown key '$unknownKey'"
                    else "$operation: ${detail.lineSequence().firstOrNull().orEmpty().substringBefore(" at path:").take(240)}"
                throw IllegalArgumentException("Message $i: $reason")
            }
            val surfaceId = when (message) {
                is CreateSurfaceMessage -> message.surfaceId.also {
                    catalogs[it] = requireNotNull(message.catalogId) { "Message $i: createSurface needs a catalogId, one of ${validator.catalogIds}" }
                }
                is UpdateComponentsMessage -> message.surfaceId
                is UpdateDataModelMessage -> message.surfaceId
                is DeleteSurfaceMessage -> error("Message $i: a surface stays in the chat; create a new one instead")
                else -> error("Message $i: only createSurface, updateComponents and updateDataModel are supported")
            }
            require(surfaceId in catalogs) { "Message $i: create surface '$surfaceId' first in this call" }
            val checked = validator.validateMessage(raw, catalogId = catalogs[surfaceId])
            if (!checked.isValid) {
                val components = when (message) {
                    is CreateSurfaceMessage -> message.components.orEmpty()
                    is UpdateComponentsMessage -> message.components
                    else -> emptyList()
                }
                val violations = components.flatMap { component ->
                    validator.validate(component, catalogs[surfaceId]).violations.map {
                        "Component '${component.id}' (${component.component}): $it"
                    }
                }
                val operation = raw.keys.first { it != "version" }
                val envelope = checked.violations.filter {
                    it.location.startsWith("/$operation/") ||
                        (it.location.isEmpty() && listOf("createSurface", "updateComponents", "updateDataModel", "deleteSurface")
                            .filter { key -> key != operation }.none { key -> "`$key` is required" in it.message })
                }.map { it.toString() }
                throw IllegalArgumentException("Message $i: ${(violations.ifEmpty { envelope.ifEmpty { listOf("invalid $operation message") } }).joinToString("; ")}")
            }
            message
        }
        val state = MessageProcessor.applyAll(RendererState(), decoded).state
        state.surfaces.values.forEach { require(it.isRenderable) { "Surface '${it.surfaceId}' has no component with id \"root\"" } }
        return "Shown to the user."
    }

    private companion object {
        fun compact(value: JsonElement): JsonElement = when (value) {
            is JsonObject -> JsonObject(value.filter { (key, item) -> key !in setOf("description", "title") || item !is JsonPrimitive }.mapValues { compact(it.value) })
            is JsonArray -> JsonArray(value.map(::compact))
            else -> value
        }

        fun catalogSummary(catalog: UiCatalog) = ProtocolJson.encodeToString(JsonObject.serializer(), buildJsonObject {
            put("catalogId", catalog.catalogId)
            catalog.instructions?.let { put("instructions", it) }
            put("components", compact(JsonObject(catalog.components)))
            put("functions", compact(JsonObject(catalog.functions)))
            catalog.defs?.let { put("\$defs", compact(it)) }
        })
    }
}
