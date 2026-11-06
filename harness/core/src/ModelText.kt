package kinuvo.harness.core

import kinuvo.protocol.modelText

import kinuvo.protocol.SessionEntry

/** What the model reads for a user turn: a Routine's prompt is labelled with the Routine's name, a voice turn as spoken. */
val SessionEntry.UserMessage.modelText: String
    get() {
        val content = action?.modelText(text) ?: text
        return routine?.let { "[Scheduled task: ${it.name}]\n\n$content" } ?: if (voice) "$VOICE_NOTE\n\n$content" else content
    }

private const val VOICE_NOTE = "[Voice chat: the user said this aloud and your reply will be read aloud. Reply briefly and conversationally, " +
    "in a few plain sentences, without Markdown, lists, tables, code or links unless they ask. This is a speech transcription, " +
    "so words may be misheard: infer what they most likely meant, and ask briefly if it's unclear.]"

/** The first [n] characters, one fewer when the cut would split a surrogate pair. */
fun String.takeWhole(n: Int): String = take(if (n in 1 until length && this[n - 1].isHighSurrogate()) n - 1 else n)
