package kinuvo.ui.e2e

import kinuvo.harness.core.plugin.HarnessPlugin
import kinuvo.harness.server.testing.FakeTool
import kinuvo.harness.server.testing.FakeTools
import kinuvo.protocol.Api
import kinuvo.protocol.ApprovalDecision
import kinuvo.protocol.Risk
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class ToolFlowTest {
    private val tools: List<HarnessPlugin> = listOf(FakeTools(
        FakeTool("read_file") { args ->
            when (val path = args.getValue("path").jsonPrimitive.content) {
                "notes.txt" -> "Fixture file contents"
                else -> error("Fixture permission denied for $path")
            }
        },
        FakeTool("shell", Risk.Medium, reason = "Runs a test command") { "test" },
    ))

    @Test fun toolSuccessShowsItsResult() = appTest("tool-success", tools, script = {
        on("Hello from Maestro") { toolCall("read_file", """{"path":"notes.txt"}"""); reply("File read completed") }
    }) {
        sendFirstMessage()
        await("File read completed")
        scrollTo("read_file")
        tap("read_file")
        await("Fixture file contents")
        screenshot("tool-result")
        requests(Api.Send, 1)
        requests(Api.CatchUp, 1)
    }

    @Test fun toolErrorShowsTheFailure() = appTest("tool-error", tools, script = {
        on("Hello from Maestro") { toolCall("read_file", """{"path":"locked.txt"}"""); reply("Could not read the file") }
    }) {
        sendFirstMessage()
        await("Could not read the file")
        scrollTo("read_file")
        tap("read_file")
        await(".*Fixture permission denied for locked.txt.*")
        screenshot("tool-error")
        requests(Api.CatchUp, 1)
    }

    @Test fun approvalAllowsOnce() = appTest("approval", tools, script = {
        on("Hello from Maestro") { toolCall("shell", """{"command":"echo test"}"""); reply("Approved action completed") }
    }) {
        sendFirstMessage()
        scrollTo("Allow shell\\?")
        await("Runs a test command")
        screenshot("approval")
        tap("Allow once")
        await("Approved action completed")
        gone("Allow once")
        screenshot("approved")
        requests(Api.Send, 1)
        requests(Api.Approve, 1)
        assertEquals(ApprovalDecision.AllowOnce, server.spy.params(Api.Approve).single().decision)
    }

    @Test fun approvalDenySendsTheNote() = appTest("approval-deny", tools, script = {
        on("Hello from Maestro") { toolCall("shell", """{"command":"echo test"}"""); reply("Action was denied") }
    }) {
        sendFirstMessage()
        scrollTo("Allow shell\\?")
        tap("Add a note for the Agent")
        tap("What to do instead")
        type("Do not run this command")
        screenshot("deny-note")
        tap("Deny")
        await("Action was denied")
        gone("Allow once")
        screenshot("denied")
        requests(Api.Connect, 1)
        requests(Api.CreateSession, 1)
        requests(Api.Send, 1)
        requests(Api.CatchUp, 1)
        requests(Api.Approve, 1)
        assertEquals(ApprovalDecision.Deny("Do not run this command"), server.spy.params(Api.Approve).single().decision)
    }
}
