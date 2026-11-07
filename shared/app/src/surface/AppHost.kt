package kinuvo.app.surface

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.*

data class AppHostState(val height: Int = 240, val displayMode: String = "inline", val ready: Boolean = false)

class AppHost(
    private val input: JsonObject,
    private val context: JsonObject,
    private val sendMessage: (String) -> Unit,
    private val openLink: (String) -> Unit,
    /** Runs an MCP App's `tools/call` on its own MCP server; null for HTML Surfaces, which have none. */
    private val callTool: (suspend (String, JsonObject) -> JsonObject)? = null,
    private val scope: CoroutineScope? = null,
    initialHeight: Int = 240,
    initialDisplayMode: String = "inline",
    toolResult: JsonObject? = null,
) {
    val state: StateFlow<AppHostState>
        field = MutableStateFlow(AppHostState(initialHeight.coerceIn(40, 560), initialDisplayMode))
    private var result = toolResult
    private var resultSent = false
    private var initialized = false
    private var displayModes: List<String>? = null
    /** Sends a message to the web content once a slower request, such as a tool call, completes. */
    var deliver: (String) -> Unit = {}

    private var generation = 0

    fun reconnect() {
        generation++
        initialized = false
        resultSent = false
        displayModes = null
        state.value = state.value.copy(ready = false)
    }

    fun updateToolResult(value: JsonObject): List<String> {
        result = value
        if (!state.value.ready || resultSent) return emptyList()
        resultSent = true
        return listOf(notification("ui/notifications/tool-result", value))
    }

    fun displayMode(mode: String): List<String> {
        if (mode !in listOf("inline", "fullscreen") || displayModes?.contains(mode) == false) return emptyList()
        state.value = state.value.copy(displayMode = mode)
        return if (state.value.ready) listOf(notification("ui/notifications/host-context-changed", buildJsonObject { put("displayMode", mode) })) else emptyList()
    }

    fun receive(raw: String): List<String> {
        if (raw.length > 512 * 1024) return listOf(failure(JsonNull, -32600, "Message too large"))
        val parsed = try { Json.parseToJsonElement(raw) } catch (_: IllegalArgumentException) {
            return listOf(failure(JsonNull, -32700, "Invalid JSON"))
        }
        val message = parsed as? JsonObject ?: return listOf(failure(JsonNull, -32600, "Invalid JSON-RPC message"))
        val id = message["id"]
        val method = (message["method"] as? JsonPrimitive)?.contentOrNull ?: return emptyList()
        if (message["jsonrpc"] != JsonPrimitive("2.0")) return listOf(failure(id ?: JsonNull, -32600, "Invalid JSON-RPC version"))
        val params = message["params"] as? JsonObject ?: JsonObject(emptyMap())
        if (id == null) {
            when (method) {
                "ui/notifications/initialized" -> if (initialized && !state.value.ready) {
                    state.value = state.value.copy(ready = true)
                    return listOf(notification("ui/notifications/tool-input", buildJsonObject { put("arguments", input) })) +
                        result?.let(::updateToolResult).orEmpty()
                }
                "ui/notifications/size-changed" -> (params["height"] as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() && it > 0 }?.let {
                    state.value = state.value.copy(height = it.coerceIn(40.0, 560.0).toInt())
                }
            }
            return emptyList()
        }
        if (method in setOf("resources/read", "ui/update-model-context"))
            return listOf(failure(id, -32601, "$method is not supported by this host"))
        if (method !in setOf("ui/initialize", "ping", "ui/message", "ui/open-link", "ui/request-display-mode", "tools/call"))
            return listOf(failure(id, -32601, "Method not found"))
        if (message["params"] != null && message["params"] !is JsonObject)
            return listOf(failure(id, -32602, "Params must be an object"))
        if (method == "tools/call") return callTool(id, params)
        var notifications = emptyList<String>()
        return try {
            val result = when (method) {
                "ui/initialize" -> {
                    require(!initialized) { "Already initialized" }
                    require(params["protocolVersion"] == JsonPrimitive("2026-01-26")) { "Unsupported protocol version" }
                    displayModes = ((params["appCapabilities"] as? JsonObject)?.get("availableDisplayModes") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                    initialized = true
                    buildJsonObject {
                        put("protocolVersion", "2026-01-26")
                        put("hostInfo", buildJsonObject { put("name", "Kinuvo"); put("version", "1") })
                        put("hostCapabilities", buildJsonObject {
                            put("openLinks", JsonObject(emptyMap()))
                            if (callTool != null) put("serverTools", JsonObject(emptyMap()))
                        })
                        put("hostContext", JsonObject(context + mapOf(
                            "displayMode" to JsonPrimitive(state.value.displayMode),
                            "availableDisplayModes" to JsonArray(listOf(JsonPrimitive("inline"), JsonPrimitive("fullscreen"))),
                        )))
                    }
                }
                "ping" -> JsonObject(emptyMap())
                else -> {
                    check(state.value.ready) { "App is not initialized" }
                    when (method) {
                        "ui/message" -> {
                            val content = params["content"] as? JsonObject
                            val text = (content?.get("text") as? JsonPrimitive)?.takeIf { it.isString }?.content
                            require(params["role"] == JsonPrimitive("user") && content?.get("type") == JsonPrimitive("text") && !text.isNullOrBlank() && text.length <= 32768) { "Invalid message format" }
                            sendMessage(text)
                            JsonObject(emptyMap())
                        }
                        "ui/open-link" -> {
                            val url = (params["url"] as? JsonPrimitive)?.content.orEmpty()
                            require(url.startsWith("https://", true) || url.startsWith("http://", true)) { "Invalid URL" }
                            openLink(url)
                            JsonObject(emptyMap())
                        }
                        "ui/request-display-mode" -> {
                            val mode = (params["mode"] as? JsonPrimitive)?.content.orEmpty()
                            notifications = displayMode(mode)
                            buildJsonObject { put("mode", state.value.displayMode) }
                        }
                        else -> return listOf(failure(id, -32601, "Method not found"))
                    }
                }
            }
            listOf(buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("result", result) }.toString()) + notifications
        } catch (error: IllegalArgumentException) {
            listOf(failure(id, -32602, error.message ?: "Invalid params"))
        } catch (error: Exception) {
            listOf(failure(id, -32000, error.message ?: "Request failed"))
        }
    }

    private fun callTool(id: JsonElement, params: JsonObject): List<String> {
        val call = callTool ?: return listOf(failure(id, -32601, "Tool calls are not available for HTML Surfaces"))
        if (!state.value.ready) return listOf(failure(id, -32000, "App is not initialized"))
        val name = (params["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val arguments = params["arguments"] ?: JsonObject(emptyMap())
        if (name.isNullOrBlank() || arguments !is JsonObject) return listOf(failure(id, -32602, "Invalid params"))
        val owner = generation
        checkNotNull(scope).launch {
            val response = try {
                buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("result", call(name, arguments)) }.toString()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failure(id, -32000, e.message ?: "Tool call failed")
            }
            if (generation == owner) deliver(response)
        }
        return emptyList()
    }

    private fun notification(method: String, params: JsonObject) = buildJsonObject {
        put("jsonrpc", "2.0"); put("method", method); put("params", params)
    }.toString()

    private fun failure(id: JsonElement, code: Int, message: String) = buildJsonObject {
        put("jsonrpc", "2.0"); put("id", id)
        put("error", buildJsonObject { put("code", code); put("message", message) })
    }.toString()
}
