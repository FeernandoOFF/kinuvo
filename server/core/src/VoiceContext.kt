package kinuvo.server

import kinuvo.protocol.session.SessionEntry
import kinuvo.server.plugin.VoiceContext
import kinuvo.server.plugin.VoiceMessage

/** The Agent the model speaks for and the Session's latest messages; older ones are left to the last compaction summary. */
internal fun voiceContext(agentName: String, entries: List<SessionEntry>): VoiceContext {
    val summary = entries.filterIsInstance<SessionEntry.Compaction>().lastOrNull()?.summary?.takeIf { it.isNotBlank() }
    val messages = entries.flatMap {
        if (it is SessionEntry.VoiceCall) it.turns.map { turn -> VoiceMessage(turn.user, turn.text) }
        else listOfNotNull(when (it) {
            is SessionEntry.UserMessage -> VoiceMessage(true, it.text)
            is SessionEntry.AssistantMessage -> VoiceMessage(false, it.text)
            else -> null
        })
    }.filter { it.text.isNotBlank() }.map { it.copy(text = it.text.take(2_000)) }
    var budget = 24_000
    val recent = messages.asReversed().takeWhile { budget -= it.text.length; budget >= 0 }.take(40).reversed()
    val instructions = buildString {
        append("You are the voice of $agentName, talking with the user. Your name is $agentName. Never say you are ChatGPT, OpenAI's assistant, or an AI from OpenAI; if asked who you are, say you are $agentName. Speak naturally and casually, using short sentences. Brief fillers like 'hmm' or 'let me check' are fine when natural. Always acknowledge the request aloud before delegating. Delegate anything needing tools, memory, the user's data or live facts to the Agent. Give short spoken updates while waiting, based on Agent progress; do not invent progress or leave a long silence. Relay approval requests and direct the user to review and approve or deny them in the app, never by voice. Relay the Agent's questions and ask the user to answer in the app. Clearly announce when results come back, then briefly explain the outcome; always tell the user the result of a delegated task.")
        if (summary != null) append("\n\nEarlier in this conversation:\n").append(summary.take(6_000))
    }
    return VoiceContext(instructions, recent)
}
