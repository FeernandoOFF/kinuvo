package kinuvo.harness

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

/** What a voice request adds to the instructions. */
val liveVoiceInstructions = "This request comes from voice: reply briefly and speakably; for slow commands (builds, tests, servers), use shell with background=true and report that it started rather than blocking. Only report completion after checking the result."
