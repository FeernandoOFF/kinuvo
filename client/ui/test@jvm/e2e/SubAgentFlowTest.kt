package kinuvo.ui.e2e

import kotlin.test.Test

class SubAgentFlowTest {
    @Test fun spawnedSubAgentOpensItsSessionAndLinksBack() = appTest("sub-agent", script = {
        on("Hello from Maestro") { toolCall("agents_spawn", """{"goal":"Summarize the fixture notes"}"""); reply("Sub-agent started") }
        on("Summarize the fixture notes") { reply("Fixture summary") }
    }) {
        sendFirstMessage()
        scrollTo("Allow agents_spawn\\?")
        tap("Allow once")
        await("Sub-agent started")
        scrollTo("Sub-agent: E2E Agent")
        await("Done")
        screenshot("spawned")
        tap("Summarize the fixture notes")
        await("Sub-agent of E2E Agent")
        await("Fixture summary")
        screenshot("child")
        tap("Open parent")
        await("Sub-agent started")
    }
}
