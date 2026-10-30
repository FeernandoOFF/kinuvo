package kinuvo.execution.acp

import java.net.URI
import kinuvo.protocol.*
import kinuvo.execution.api.ExecutionBroker
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

private fun JsonElement?.text() = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonElement?.shown() = text()?.takeIf { it.isNotBlank() }

private fun host(url: String) = url.takeIf { "://" in it }?.let { runCatching { URI(it).host }.getOrNull() }?.takeIf { it.isNotBlank() }

/** Shows only the host of any address in [text]. */
private fun withoutAddresses(text: String) = Regex("""[a-zA-Z][a-zA-Z0-9+.-]*://\S+""").replace(text) { host(it.value) ?: "a link" }

internal fun ExecutionRequest.decodePrompt(): ExecutionPrompt {
    val body = payload as? JsonObject ?: JsonObject(emptyMap())
    if (kind == ExecutionRequestKind.Approval) {
        // A linked Kinuvo Server forwards its own Approval: the tool and its arguments. ACP names the tool call it's about as its subject.
        val call = ((body["subject"] as? JsonObject)?.get("toolCall") ?: body["toolCall"]) as? JsonObject
        val input = call?.get("rawInput") as? JsonObject
        val content = (call?.get("content") as? JsonArray)?.firstNotNullOfOrNull { ((it as? JsonObject)?.get("content") as? JsonObject)?.get("text").shown() }
        val title = body["title"].shown() ?: call?.get("title").shown() ?: body["toolCallName"].shown() ?: "Wants to act"
        val description = (body["description"].shown() ?: body["reason"].shown())?.let(::withoutAddresses) ?: body["args"].shown()?.let(::withoutAddresses)
            ?: (input?.get("description").shown() ?: content ?: call?.get("title").shown()?.takeIf { it != title })?.let(::withoutAddresses)
        return ExecutionPrompt.Approval(id, withoutAddresses(title), description, options.map { it.copy(label = withoutAddresses(it.label)) }, input?.get("url").text()?.let(::host), input?.get("serverName").shown()?.let(::withoutAddresses))
    }
    if ("questionsId" in body && "questions" in body) runCatching { ProtocolJson.decodeFromJsonElement(AgentEvent.QuestionsAsked.serializer(), body) }.getOrNull()?.let {
        return ExecutionPrompt.Questions(id, it.copy(questionsId = id), native = true)
    }
    val message = body["message"].text() ?: "Questions for you"
    if (body["mode"].text() == "url") {
        val url = body["url"].text()?.takeIf { value -> runCatching {
            val uri = URI(value)
            uri.scheme in setOf("https", "http") && !uri.host.isNullOrBlank() && uri.rawUserInfo == null
        }.getOrDefault(false) }
        val site = url?.let(::host) ?: return ExecutionPrompt.Unsupported(id, withoutAddresses(message))
        return ExecutionPrompt.OpenLink(id, withoutAddresses(message), url, site)
    }
    val schema = body["requestedSchema"] as? JsonObject ?: return ExecutionPrompt.Unsupported(id, withoutAddresses(message))
    val properties = schema["properties"] as? JsonObject ?: return ExecutionPrompt.Unsupported(id, withoutAddresses(message))
    if (properties.values.any { value ->
        val property = value as? JsonObject
        property?.get("type").text() !in setOf("string", "number", "integer", "boolean", "array") ||
            (property?.get("type").text() == "array" && ((property?.get("items") as? JsonObject)?.get("type").text() !in setOf(null, "string")))
    }) return ExecutionPrompt.Unsupported(id, withoutAddresses(message))
    val required = (schema["required"] as? JsonArray)?.mapNotNull { it.text() }.orEmpty()
    val types = mutableMapOf<String, String>()
    val values = mutableMapOf<String, Map<String, String>>()
    val questions = (schema["properties"] as? JsonObject).orEmpty().map { (key, value) ->
        val p = value as? JsonObject ?: JsonObject(emptyMap())
        val type = p["type"].text().orEmpty()
        types[key] = type
        val choices = choices(if (type == "array") p["items"] as? JsonObject else p)
        if (choices.isNotEmpty()) values[key] = choices
        val kind = when {
            type == "boolean" -> QuestionKind.YesNo
            type == "array" -> QuestionKind.Multi
            choices.isNotEmpty() -> QuestionKind.Single
            type == "number" || type == "integer" -> QuestionKind.Number
            p["format"].text() == "date" -> QuestionKind.Date
            else -> QuestionKind.Text
        }
        val default = p["default"]?.let { d -> (d as? JsonArray)?.mapNotNull { it.text() } ?: listOfNotNull((d as? JsonPrimitive)?.content) }.orEmpty()
            .map { v -> choices.entries.firstOrNull { it.value == v }?.key ?: if (kind == QuestionKind.YesNo) (if (v == "true") "Yes" else "No") else v }
        Question(key, withoutAddresses(p["title"].text() ?: key), kind, choices.keys.toList(), key in required, p["description"].text()?.let(::withoutAddresses), default)
    }
    return ExecutionPrompt.Questions(id, AgentEvent.QuestionsAsked(id, id, withoutAddresses(message), questions), types, values)
}

/** Option label to value, from `enum` (label = value) or titled `oneOf`/`anyOf` options. */
private fun choices(p: JsonObject?): Map<String, String> {
    if (p == null) return emptyMap()
    (p["oneOf"] ?: p["anyOf"])?.let { options ->
        return (options as? JsonArray).orEmpty().mapNotNull { o ->
            val obj = o as? JsonObject ?: return@mapNotNull null
            val value = obj["const"].text() ?: return@mapNotNull null
            (obj["title"].text() ?: value) to value
        }.toMap()
    }
    return (p["enum"] as? JsonArray)?.mapNotNull { it.text() }?.associateWith { it }.orEmpty()
}

internal fun ExecutionPrompt.encodeAnswer(answer: JsonElement): JsonElement {
    if (this is ExecutionPrompt.Approval) return buildJsonObject {
        val option = if (answer == JsonNull) null else answer.jsonObject["optionId"]?.jsonPrimitive?.content
        require(answer == JsonNull || options.any { it.id == option }) { "Unknown Approval option" }
        putJsonObject("outcome") {
            put("outcome", if (option == null) "cancelled" else "selected")
            option?.let { put("optionId", it) }
        }
    }
    if (answer == JsonNull) return buildJsonObject { put("action", "decline") }
    return when (this) {
        is ExecutionPrompt.OpenLink -> buildJsonObject {
            val opened = requireNotNull(answer.jsonObject["opened"]?.jsonPrimitive?.booleanOrNull)
            put("action", if (opened) "accept" else "decline")
        }
        is ExecutionPrompt.Questions -> buildJsonObject {
            val answers = requireNotNull(answer.jsonObject["answers"] as? JsonObject)
            require(answers.keys.all { key -> asked.questions.any { it.id == key } }) { "Unknown question" }
            put("action", "accept")
            putJsonObject("content") {
                asked.questions.forEach { q ->
                    val raw = (answers[q.id] as? JsonArray).orEmpty().map {
                        require(it is JsonPrimitive && it.isString) { "Answers must be strings" }; it.content
                    }.filter { it.isNotBlank() }
                    require(!q.required || raw.isNotEmpty()) { "Required answer missing" }
                    if (raw.isEmpty()) return@forEach
                    require(q.options.isEmpty() || raw.all { it in q.options }) { "Unknown option" }
                    require(q.kind == QuestionKind.Multi || raw.size == 1) { "Expected one answer" }
                    val encoded = raw.map { values[q.id]?.get(it) ?: it }
                    when (types[q.id]) {
                        "array" -> put(q.id, JsonArray(encoded.map(::JsonPrimitive)))
                        "boolean" -> { require(raw.single() in listOf("Yes", "No")); put(q.id, raw.single() == "Yes") }
                        "integer" -> put(q.id, requireNotNull(encoded.single().trim().toLongOrNull()))
                        "number" -> { val number = requireNotNull(encoded.single().trim().toDoubleOrNull()); require(number.isFinite()); put(q.id, number) }
                        else -> put(q.id, encoded.single())
                    }
                }
            }
        }
        else -> error("Unsupported request can only be declined")
    }
}

internal class AcpRequests(private val broker: ExecutionBroker) {
    private val lock = Mutex()
    private val waiting = mutableSetOf<Job>()

    suspend fun ask(source: ExecutionRequest, prompt: ExecutionPrompt): JsonElement = coroutineScope {
        val job = currentCoroutineContext().job
        lock.withLock { waiting.add(job) }
        try { broker.ask(source) { prompt.encodeAnswer(it) } }
        finally { withContext(NonCancellable) { lock.withLock { waiting.remove(job) } } }
    }

    suspend fun cancel() {
        val jobs = lock.withLock { waiting.toList() }
        jobs.forEach { it.cancel() }
        jobs.forEach { it.join() }
    }
}
