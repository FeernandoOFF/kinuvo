package kinuvo.app.session

import kinuvo.protocol.*
import kotlinx.serialization.json.*

fun ExecutionRequest.displayPrompt(): ExecutionPrompt = prompt ?: ExecutionPrompt.Unsupported(id, "Unsupported request")

fun approvalAnswer(optionId: String?): JsonElement = optionId?.let {
    buildJsonObject { put("optionId", it) }
} ?: JsonNull

fun openLinkAnswer(opened: Boolean): JsonElement = buildJsonObject { put("opened", opened) }

fun questionsAnswer(prompt: ExecutionPrompt, answers: Map<String, List<String>>?): JsonElement =
    if (answers == null || prompt !is ExecutionPrompt.Questions) JsonNull else buildJsonObject {
        putJsonObject("answers") { answers.forEach { (id, values) -> put(id, JsonArray(values.map(::JsonPrimitive))) } }
    }

fun resumeOnSend(phase: ExecutionPhase?, capabilities: SessionCapabilities?): Boolean =
    capabilities?.execution?.resume == true && capabilities.chat.reasonCode in setOf(null, "session_lost") &&
        (phase == ExecutionPhase.Lost || capabilities.chat.reasonCode == "session_lost")

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
