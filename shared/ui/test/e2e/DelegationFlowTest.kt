package kinuvo.ui.e2e

import kotlin.test.Test

class DelegationFlowTest {
    private val delegate = """{"type":"tool_call","id":"e2e-delegate","name":"delegate","args":"{\"tasks\":[{\"goal\":\"Summarize the bank statement\",\"hidden\":true},{\"goal\":\"Draft a reply to Sam\"}]}"}"""

    private fun child(id: String, title: String, status: String, hidden: Boolean) =
        """{"type":"event","event":"session.changed","payload":{"id":"$id","agentId":"e2e","title":"$title","status":"$status","hidden":$hidden,"createdAt":${if (hidden) 1 else 2},"parent":{"agentId":"e2e","sessionId":"e2e-session","depth":1,"toolCallId":"e2e-delegate"}},"seq":20}"""

    @Test fun delegationShowsItsSessions() = appTest("delegation", "chat") {
        server.stub("session-children", "session.children", "[]", 1)
        startChat()
        push(sessionEvent(5, """{"type":"RUN_STARTED","runId":"delegate-run"}"""))
        push(sessionEntry(6, 2, delegate))
        await("Delegated 2 tasks")
        screenshot("starting")
        push(child("child-hidden", "Summarize the bank statement", "Running", hidden = true))
        push(child("child-reply", "Draft a reply to Sam", "Waiting", hidden = false))
        await("Hidden")
        await("E2E Agent needs you · 2 delegated Sessions")
        screenshot("running")
        requestsAtLeast("session-children")
    }

    @Test fun approvalShowsWhatChanges() = appTest("change-preview", "chat") {
        startChat()
        push(sessionEvent(5, """{"type":"RUN_STARTED","runId":"manage-run"}"""))
        push(sessionEntry(6, 2, """{"type":"tool_call","id":"e2e-update","name":"agent_update","args":"{\"name\":\"Ada\",\"soul\":\"Be brief.\"}"}"""))
        push(sessionEvent(7, """{"type":"APPROVAL_REQUESTED","approvalId":"e2e-approval","toolCallId":"e2e-update","toolCallName":"agent_update","args":"{\"name\":\"Ada\",\"soul\":\"Be brief.\"}","risk":"High","reason":"Changes this Agent's own settings\nChanges to e2e:\n- Name: E2E Agent → Ada\n- Soul: \"Explain everything in detail. Use examples.\" (43 characters) → Be brief.","trustable":false,"preview":{"title":"Changes to e2e","changes":[{"field":"Name","before":"E2E Agent","after":"Ada"},{"field":"Soul","before":"Explain everything in detail.\nUse examples.","after":"Be brief."}]}}"""))
        scrollTo("Allow agent_update\\?")
        await("Changes to e2e")
        await("Ada")
        gone("Changes to e2e:")
        screenshot("change-preview")
    }
}
