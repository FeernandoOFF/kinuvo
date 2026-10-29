package kinuvo.app.session

import kinuvo.protocol.*
import kotlinx.serialization.json.*

/** What an external Agent's harness waits on in a Session, as the Session screen shows it. */
sealed interface ExternalPrompt {
    val id: String

    /**
     * A permission request, answered with one of the harness's own [options]. [site] is the host of a page it would open,
     * never the full address, which may carry a sign-in token; [server] is the MCP server asking.
     */
    data class Approval(
        override val id: String, val title: String, val description: String?, val options: List<ExternalApprovalOption>,
        val site: String? = null, val server: String? = null,
    ) : ExternalPrompt {
        /** Where it leads and who asks, e.g. "Opens example.com · from slack". */
        val origin get() = listOfNotNull(site?.let { "Opens $it" }, server?.let { "from $it" }).joinToString(" · ").replaceFirstChar { it.uppercase() }.ifEmpty { null }
    }

    /**
     * A form, shown like an `ask_user` form; [values] maps each option label back to the value the harness expects.
     * [native] marks a linked Kinuvo Server's own `ask_user` form, answered as Kinuvo answers it.
     */
    data class Questions(
        override val id: String, val asked: AgentEvent.QuestionsAsked, val types: Map<String, String> = emptyMap(),
        val values: Map<String, Map<String, String>> = emptyMap(), val native: Boolean = false,
    ) : ExternalPrompt

    /**
     * A request to open a page, e.g. to sign in to a service the harness uses. Nothing opens until the user chooses
     * to; [site] is what the card shows, never the full [url].
     */
    data class OpenLink(override val id: String, val message: String, val url: String, val site: String) : ExternalPrompt

    /** A request this client can't fill in; it can only be declined. */
    data class Unsupported(override val id: String, val message: String) : ExternalPrompt
}

private fun JsonElement?.text() = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonElement?.shown() = text()?.takeIf { it.isNotBlank() }

private fun host(url: String) = url.takeIf { "://" in it }?.let { runCatching { io.ktor.http.Url(it).host }.getOrNull() }?.takeIf { it.isNotBlank() }

/** Shows only the host of any address in [text]. */
private fun withoutAddresses(text: String) = Regex("""[a-zA-Z][a-zA-Z0-9+.-]*://\S+""").replace(text) { host(it.value) ?: "a link" }

fun ExecutionRequest.prompt(): ExternalPrompt {
    val body = payload as? JsonObject ?: JsonObject(emptyMap())
    if (kind == ExecutionRequestKind.Approval) {
        // A linked Kinuvo Server forwards its own Approval: the tool and its arguments. ACP names the tool call it's about as its subject.
        val call = ((body["subject"] as? JsonObject)?.get("toolCall") ?: body["toolCall"]) as? JsonObject
        val input = call?.get("rawInput") as? JsonObject
        val content = (call?.get("content") as? JsonArray)?.firstNotNullOfOrNull { ((it as? JsonObject)?.get("content") as? JsonObject)?.get("text").shown() }
        val title = body["title"].shown() ?: call?.get("title").shown() ?: body["toolCallName"].shown() ?: "Wants to act"
        val description = (body["description"].shown() ?: body["reason"].shown())?.let(::withoutAddresses) ?: body["args"].shown()?.let(::withoutAddresses)
            ?: (input?.get("description").shown() ?: content ?: call?.get("title").shown()?.takeIf { it != title })?.let(::withoutAddresses)
        return ExternalPrompt.Approval(id, withoutAddresses(title), description, options, input?.get("url").text()?.let(::host), input?.get("serverName").shown())
    }
    if ("questionsId" in body && "questions" in body) runCatching { ProtocolJson.decodeFromJsonElement(AgentEvent.QuestionsAsked.serializer(), body) }.getOrNull()?.let {
        return ExternalPrompt.Questions(id, it.copy(questionsId = id), native = true)
    }
    val message = body["message"].text() ?: "Questions for you"
    if (body["mode"].text() == "url") {
        val url = body["url"].text()?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
        val site = url?.let(::host) ?: return ExternalPrompt.Unsupported(id, withoutAddresses(message))
        return ExternalPrompt.OpenLink(id, withoutAddresses(message), url, site)
    }
    val schema = body["requestedSchema"] as? JsonObject ?: return ExternalPrompt.Unsupported(id, message)
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
        Question(key, p["title"].text() ?: key, kind, choices.keys.toList(), key in required, p["description"].text(), default)
    }
    return ExternalPrompt.Questions(id, AgentEvent.QuestionsAsked(id, id, message, questions), types, values)
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

/** The harness's answer to a permission request: the chosen option, or cancelled. */
fun approvalAnswer(optionId: String?): JsonElement = buildJsonObject {
    putJsonObject("outcome") {
        if (optionId == null) put("outcome", "cancelled") else { put("outcome", "selected"); put("optionId", optionId) }
    }
}

/** Tells the harness the user opened the page it asked for, or declined to. */
fun openLinkAnswer(opened: Boolean): JsonElement = buildJsonObject { put("action", if (opened) "accept" else "decline") }

/** Accepts [prompt] with [answers] in the form's own types, or declines it when null; a Kinuvo form gets Kinuvo's answers, null skipping it. */
fun questionsAnswer(prompt: ExternalPrompt, answers: Map<String, List<String>>?): JsonElement =
    if (prompt is ExternalPrompt.Questions && prompt.native) ProtocolJson.encodeToJsonElement(QuestionsParams.serializer(), QuestionsParams(prompt.id, answers))
    else elicitationAnswer(prompt, answers)

private fun elicitationAnswer(prompt: ExternalPrompt, answers: Map<String, List<String>>?): JsonElement = buildJsonObject {
    if (answers == null || prompt !is ExternalPrompt.Questions) { put("action", "decline"); return@buildJsonObject }
    put("action", "accept")
    putJsonObject("content") {
        prompt.asked.questions.forEach { q ->
            val raw = answers[q.id].orEmpty().filter { it.isNotBlank() }.map { prompt.values[q.id]?.get(it) ?: it }
            if (raw.isEmpty()) return@forEach
            when (prompt.types[q.id]) {
                "array" -> put(q.id, JsonArray(raw.map(::JsonPrimitive)))
                "boolean" -> put(q.id, raw.first() == "Yes")
                "integer" -> raw.first().trim().toLongOrNull()?.let { put(q.id, it) }
                "number" -> raw.first().trim().toDoubleOrNull()?.let { put(q.id, it) }
                else -> put(q.id, raw.first())
            }
        }
    }
}

/**
 * Why an external Session can't take messages any more: its harness process ended ([closed] when the user closed it)
 * or it is closed. [resume] is whether its harness can reopen saved Sessions, null when this Device can't tell.
 */
data class SessionEnd(val closed: Boolean, val resume: Boolean?) {
    val pill get() = if (closed) "Closed" else "Session ended"

    fun message(harness: String, host: String) = (if (closed) "You closed this Session." else "$harness stopped running this Session on $host.") + " " +
        when (resume) {
            false -> "Its transcript stays here, but $harness can't resume Sessions, so this one can't continue. Start a new Session to keep going."
            true -> "Its transcript stays here. Resume asks $harness on $host to reopen it with the context it saved."
            null -> "Its transcript stays here. Resume asks $harness on $host to reopen it, if it can."
        }

    /** What the composer says instead of sending. */
    val blocked get() = if (resume == false) "Start a new Session to keep chatting." else "Resume this Session to keep chatting."

    companion object {
        /** From the harness's [phase] or, where this Device can't see it, why chat is off; null while the Session runs. */
        fun of(phase: ExecutionPhase?, chat: CapabilityState?, resume: Boolean?): SessionEnd? {
            val code = chat?.reasonCode
            if (code == "session_closed") return SessionEnd(true, resume)
            if (phase == ExecutionPhase.Lost || code == "session_lost") return SessionEnd(false, resume)
            return null
        }
    }
}
