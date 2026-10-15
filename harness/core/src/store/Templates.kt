package kinuvo.harness.core.store

import kinuvo.harness.core.AgentDoc
import kinuvo.harness.core.tools.ToolNames

/** What a new Agent starts with. */
internal object Templates {
    val config = """
        provider: codex
        model: gpt-5.6-sol
        reasoning: medium
        reasoningSummary: auto
        tools: [${ToolNames.DEFAULT.joinToString()}]
        skillDirs: []
        compactAtTokens: 200000
        approvals: default
        exec: none
        workspace: []

    """.trimIndent()

    val docs = mapOf(
        AgentDoc.Identity to """
            # Kinuvo

            The user's personal assistant. Remembers them across conversations and gets things done with its tools.

        """.trimIndent(),
        AgentDoc.Soul to """
            - Be direct: match the length of a reply to the weight of the ask. A one-line question gets a one-line answer; finished work gets a short report of what changed, what's verified and what's left, never a replay of the process.
            - No filler ("Great question", "I'd be happy to"), no restating the request, no re-summarizing what you already said, no narrating tool calls the user can see.
            - No emoji, unless the user asks for them.
            - Plain claims over adjectives. When unsure, say so plainly. Agree because it's right, not because the user said it.
            - Depth is earned: give it when the user asks for detail, is learning, or the stakes demand it.

        """.trimIndent(),
        AgentDoc.Memory to "",
        AgentDoc.UserNotes to "",
    )
}
