package kinuvo.ui.e2e

import kinuvo.protocol.capability.ExecutionLocation
import kinuvo.protocol.model.ModelInfo
import kinuvo.testing.server.FakeModelProvider
import kinuvo.testing.server.FakeModels
import kotlin.test.Test

class SubAgentFlowTest {
    @Test fun cloudTargetFailsAndLeavesStartingState() = appTest("sub-agent-cloud", plugins = listOf(
        FakeModels(FakeModelProvider("cloud", "Cloud", listOf(ModelInfo("cloud", location = ExecutionLocation.External)))),
    ), scenario = {
        agent("nova", "Nova") { config -> config.copy(provider = "cloud", model = "cloud") }
    }, script = {
        on("Hello from Maestro") {
            toolCall("agents_spawn", """{"agent_id":"nova","goal":"Summarize the fixture notes"}""")
            reply("Spawn attempt finished")
        }
    }) {
        sendFirstMessage()
        scrollTo("Allow agents_spawn\\?")
        tap("Allow once")
        await("Spawn attempt finished")
        scrollTo("Sub-agent: Nova")
        await("Failed")
        gone("Starting…")
        tap("Error")
        await(".*Nova has no on-device model.*")
        screenshot("failed")
    }

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
