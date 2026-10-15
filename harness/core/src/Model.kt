package kinuvo.harness.core

import kinuvo.protocol.SessionEntry

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.streaming.StreamFrame
import kotlinx.coroutines.flow.Flow

/** Streams one model turn given the frozen instructions and the Session transcript so far. */
fun interface Model {
    fun stream(instructions: String, history: List<SessionEntry>, tools: List<ToolDescriptor>): Flow<StreamFrame>
}

/** What the model reads for a user turn: a Routine's prompt is labelled with the Routine's name, a voice turn as spoken. */
val SessionEntry.UserMessage.modelText: String
    get() = routine?.let { "[Scheduled task: ${it.name}]\n\n$text" } ?: if (voice) "$VOICE_NOTE\n\n$text" else text

private const val VOICE_NOTE = "[Voice chat: the user said this aloud and your reply will be read aloud. Reply briefly and conversationally, " +
    "in a few plain sentences, without Markdown, lists, tables, code or links unless they ask. This is a speech transcription, " +
    "so words may be misheard: infer what they most likely meant, and ask briefly if it's unclear.]"
