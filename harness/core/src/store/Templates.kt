package kinuvo.harness.core.store

import kinuvo.harness.core.AgentDoc

/** What a new Agent starts with. */
internal object Templates {
    val config = """
        provider: codex
        model: gpt-5.6-sol
        reasoning: medium
        reasoningSummary: auto
        tools: [memory, web_fetch, skills, schedule, knowledge, tasks, device_tools, ask_user]
        skillDirs: []
        compactAtTokens: 200000
        approvals: default
        exec: none
        workspace: []

    """.trimIndent()

    val docs = mapOf(
        AgentDoc.Identity to """
            # Kinuvo

            A personal assistant. Curious, warm, direct. Emoji: 🧭

        """.trimIndent(),
        AgentDoc.Soul to """
            - Match detail to the request. Answer simple questions briefly; explain more when the user asks or the stakes require it.
            - Be warm and direct. Skip filler, flattery and repeated narration.
            - Say when you are unsure; distinguish facts from assumptions and suggestions.
            - Disagree plainly when the evidence warrants it.

        """.trimIndent(),
        AgentDoc.Memory to "",
        AgentDoc.UserNotes to "",
    )
}
