package kinuvo.ui.e2e

import kinuvo.protocol.Frame
import kinuvo.protocol.FrameConnection
import kinuvo.protocol.ProtocolJson
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.*

/**
 * Serves a Mockstro WireMock profile in-process: `.maestro/wiremock/profiles/<profile>.json` and its WebSocket stubs.
 * Each request is answered by the matching stub with the best priority, then the most specific; [hits] counts matches per stub like Mockstro's `apiVerifications`.
 */
internal class MockServer(profile: String) {
    private val stubs = MutableStateFlow(Json.parseToJsonElement(fixture("profiles/$profile.json")).jsonObject.getValue("mappings").jsonArray
        .map { Stub.parse(Json.parseToJsonElement(fixture("stubs/${it.jsonPrimitive.content}.json")).jsonObject) }
        .filterNotNull())
    private val open = MutableStateFlow<List<Channel<Frame>>>(emptyList())
    val hits = MutableStateFlow<Map<String, Int>>(emptyMap())
    val unmatched = MutableStateFlow<List<String>>(emptyList())

    fun hits(stub: String) = hits.value[stub] ?: 0

    suspend fun connect(@Suppress("UNUSED_PARAMETER") url: String): FrameConnection {
        val frames = Channel<Frame>(Channel.UNLIMITED)
        open.update { it + frames }
        return object : FrameConnection {
            override val incoming = frames.receiveAsFlow()
            override suspend fun send(frame: Frame) {
                val body = Json.parseToJsonElement(ProtocolJson.encodeToString(Frame.serializer(), frame))
                val stub = stubs.value.filter { it.matches(body) }
                    .sortedWith(compareBy<Stub> { it.priority ?: Int.MAX_VALUE }.thenByDescending { it.conditions.size }).firstOrNull()
                if (stub == null) { unmatched.update { it + body.toString() }; return }
                hits.update { it + (stub.name to (it[stub.name] ?: 0) + 1) }
                stub.responses.forEach { frames.send(decode(template(it, body))) }
            }
            override suspend fun close() { open.update { it - frames }; frames.close() }
        }
    }

    /** Adds a stub answering [method] with [payload], like Mockstro scripts posting to `/__admin/message-mappings`. */
    fun stub(name: String, method: String, payload: String, priority: Int) {
        val response = """{"type":"res","id":"{{jsonPath message.body '${'$'}.id'}}","ok":true,"payload":$payload}"""
        stubs.update { it + Stub(name, listOf(Condition("${'$'}.method", buildJsonObject { put("equalTo", method) })), listOf(response), priority) }
    }

    /** Sends a Server message to every open connection, like `shared/send-message.js`. */
    suspend fun push(json: String) = open.value.forEach { it.send(decode(json)) }

    private fun decode(json: String) = ProtocolJson.decodeFromString(Frame.serializer(), json)

    private fun template(text: String, body: JsonElement) = TEMPLATE.replace(text) { match ->
        when (val value = body.at(match.groupValues[1])) {
            is JsonPrimitive -> value.content
            null -> ""
            else -> value.toString()
        }
    }

    private class Condition(val path: String, val check: JsonObject) {
        fun matches(body: JsonElement): Boolean {
            val value = body.at(path) ?: return false
            val text = (value as? JsonPrimitive)?.content ?: value.toString()
            return check["equalTo"]?.let { text == it.jsonPrimitive.content }
                ?: check["matches"]?.let { Regex(it.jsonPrimitive.content).matches(text) }
                ?: check["equalToJson"]?.let { value == Json.parseToJsonElement(it.jsonPrimitive.content) }
                ?: error("Unsupported matcher in $check")
        }
    }

    private class Stub(val name: String, val conditions: List<Condition>, val responses: List<String>, val priority: Int? = null) {
        fun matches(body: JsonElement) = conditions.all { it.matches(body) }

        companion object {
            fun parse(json: JsonObject): Stub? {
                val trigger = json["trigger"]?.jsonObject ?: return null
                val body = trigger.getValue("message").jsonObject.getValue("body").jsonObject
                val conditions = (body["and"]?.jsonArray?.map { it.jsonObject } ?: listOf(body)).map {
                    val path = it.getValue("matchesJsonPath").jsonObject
                    Condition(path.getValue("expression").jsonPrimitive.content, path)
                }
                val responses = json.getValue("actions").jsonArray.map {
                    it.jsonObject.getValue("message").jsonObject.getValue("body").jsonObject.getValue("data").jsonPrimitive.content
                }
                return Stub(json.getValue("name").jsonPrimitive.content, conditions, responses, json["priority"]?.jsonPrimitive?.int)
            }
        }
    }

    private companion object {
        val TEMPLATE = Regex("""\{\{jsonPath message\.body '([^']+)'}}""")
    }
}

/** Evaluates a simple JSONPath such as `$.params.input.actions[0].key`. */
private fun JsonElement.at(path: String): JsonElement? =
    Regex("""\.([A-Za-z_][A-Za-z0-9_]*)|\[(\d+)]""").findAll(path.removePrefix("$")).fold(this as JsonElement?) { node, step ->
        val (key, index) = step.destructured
        if (key.isNotEmpty()) (node as? JsonObject)?.get(key) else (node as? JsonArray)?.getOrNull(index.toInt())
    }
