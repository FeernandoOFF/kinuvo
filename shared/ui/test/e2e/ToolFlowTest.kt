package kinuvo.ui.e2e

import kotlin.test.Test

class ToolFlowTest {
    private val shellCall = """{"type":"tool_call","id":"e2e-tool","name":"shell","identity":{"instance":{"pluginId":"kinuvo.exec","instanceId":"default"},"localId":"shell"},"args":"{\"command\":\"echo test\"}"}"""
    private val approval = """{"type":"APPROVAL_REQUESTED","approvalId":"e2e-approval","toolCallId":"e2e-tool","toolCallName":"shell","identity":{"instance":{"pluginId":"kinuvo.exec","instanceId":"default"},"localId":"shell"},"args":"{\"command\":\"echo test\"}","reason":"Test approval from WireMock","trustable":false}"""

    private suspend fun AppTest.toolRun(output: String, error: Boolean, reply: String) {
        push(sessionEvent(5, """{"type":"RUN_STARTED","runId":"tool-run"}"""))
        push(sessionEntry(6, 2, """{"type":"tool_call","id":"read-tool","name":"read_file","args":"{\"path\":\"notes.txt\"}"}"""))
        push(sessionEntry(7, 3, """{"type":"tool_result","id":"read-tool","name":"read_file","output":"$output","isError":$error}"""))
        push(sessionEntry(8, 4, """{"type":"assistant","text":"$reply","messageId":"tool-reply"}"""))
        push(sessionEvent(9, """{"type":"RUN_FINISHED","runId":"tool-run"}"""))
    }

    @Test fun toolSuccessShowsItsResult() = appTest("tool-success", "chat") {
        startChat()
        toolRun("Fixture file contents", error = false, reply = "File read completed")
        await("File read completed")
        scrollTo("read_file")
        tap("read_file")
        await("Fixture file contents")
        screenshot("tool-result")
        requests("ws/chat-send", 1)
        requests("ws/catch-up", 1)
    }

    @Test fun toolErrorShowsTheFailure() = appTest("tool-error", "chat") {
        startChat()
        toolRun("Fixture permission denied", error = true, reply = "Could not read the file")
        await("Could not read the file")
        scrollTo("read_file")
        tap("read_file")
        await("Fixture permission denied")
        screenshot("tool-error")
        requests("ws/catch-up", 1)
    }

    @Test fun approvalStreamsAndAllowsOnce() = appTest("approval-streaming", "mobile-approval") {
        startChat()
        push(sessionEvent(5, """{"type":"RUN_STARTED","runId":"approval-run"}"""))
        push(sessionEvent(6, """{"type":"TEXT_MESSAGE_CONTENT","messageId":"approval-reply","delta":"Streaming draft"}"""))
        await("Streaming draft")
        screenshot("streaming")
        push(sessionEntry(7, 2, shellCall))
        push(sessionEvent(8, approval))
        scrollTo("Run this command\\?")
        await("Test approval from WireMock")
        screenshot("approval")
        tap("Allow once")
        await("Approved action completed")
        gone("Allow once")
        gone("Streaming draft")
        screenshot("approved")
        requests("ws/chat-send", 1)
        requests("mobile/approval-allow", 1)
    }

    @Test fun approvalDenySendsTheNote() = appTest("approval-deny", "mobile-denial") {
        startChat()
        push(sessionEvent(5, """{"type":"RUN_STARTED","runId":"approval-run"}"""))
        push(sessionEntry(7, 2, shellCall))
        push(sessionEvent(8, approval))
        scrollTo("Run this command\\?")
        tap("Add a note for the Agent")
        tap("What to do instead")
        type("Do not run this command")
        screenshot("deny-note")
        tap("Deny")
        await("Action was denied")
        gone("Allow once")
        screenshot("denied")
        requests("ws/connect", 1)
        requests("ws/create-session", 1)
        requests("ws/chat-send", 1)
        requests("ws/catch-up", 1)
        requests("mobile/approval-deny", 1)
        requests("mobile/approval-allow", 0)
    }
}
