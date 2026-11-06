package kinuvo.harness.core.tools

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
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
import kinuvo.protocol.Risk
import kinuvo.protocol.UiCatalog
import kinuvo.protocol.uiMessages
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*

/** Shows the user an A2UI surface in the chat, checked against the catalogs their Devices render. */
class ShowUiTool(catalogs: List<UiCatalog>) : Tool {
    private val validator = CatalogValidator.of(catalogs.map {
        A2uiJson.lenient.decodeFromJsonElement(CatalogDefinition.serializer(), ProtocolJson.encodeToJsonElement(UiCatalog.serializer(), it))
    })

    override val risk = Risk.Low
    override val descriptor = ToolDescriptor(
        name = ToolNames.SHOW_UI,
        description = "Show the user native UI (A2UI v1.0) in the chat: a layout, a form or choices with buttons. " +
            "The Kinuvo catalog https://kinuvo.com/a2ui/v1/catalog.json includes all basic components plus Badge(label), Spacer(minSize), " +
            "ListItem(title, subtitle, leading/trailing child IDs, action), Table/TableRow/TableCell(children), and Transition(child). " +
            "Table children can use A2UI templates bound to arrays; cells accept arbitrary components. " +
            "Build forms with bound inputs and Button checks/actions. Compose card status from Badge and confirm/cancel from Buttons inside the Card child. " +
            "Use Text variants h1-h5 for titles, caption for captions; TextField longText for textareas; ChoicePicker for selects/radios. " +
            "Each call creates new surfaces: start with a createSurface (surfaceId, catalogId, components, " +
            "dataModel), then optional updateComponents/updateDataModel for it. Components are a flat list; one has id \"root\", " +
            "and the others are referenced by id from child/children; createSurface has no root key. Bind values with {\"path\": \"/json/pointer\"}; inputs write to " +
            "their bound path. Checks wrap the function in condition, e.g. \"checks\":[{\"condition\":{\"call\":\"required\",\"args\":{\"value\":{\"path\":\"/name\"}}},\"message\":\"Required\"}]. A Button's action is {\"event\": {\"name\": ..., \"context\": {key: literal or {\"path\": ...}}}}; when the " +
            "user presses it you get the event's userMessage (or name), followed by a Surface action annotation identifying the action, surface, component and call, and its resolved Context JSON. The Session stores the action and data model separately from the displayed text.\n\nCatalogs:\n" +
            catalogs.joinToString("\n\n") { catalogSummary(it) },
        requiredParameters = listOf(
            ToolParameterDescriptor("messages", "A2UI messages, e.g. [{\"createSurface\": {...}}]",
                ToolParameterType.List(ToolParameterType.Object(emptyList(), additionalProperties = true))),
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
