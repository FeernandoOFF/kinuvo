package kinuvo.ui.e2e

import kotlin.test.Test

class SecretFlowTest {
    private val call = """{"type":"tool_call","id":"e2e-secret","name":"request_secret","args":"{\"label\":\"GitHub token\",\"reason\":\"To open the pull request\"}"}"""
    private val asked = """{"type":"SECRET_REQUESTED","requestId":"e2e-request","toolCallId":"e2e-secret","label":"GitHub token","reason":"To open the pull request"}"""

    private suspend fun AppTest.askForSecret() {
        server.stub("secret-respond", "secret.respond", "true", 1)
        startChat()
        push(sessionEvent(5, """{"type":"RUN_STARTED","runId":"secret-run"}"""))
        push(sessionEntry(6, 2, call))
        push(sessionEvent(7, asked))
        await("Share GitHub token")
    }

    @Test fun typedSecretIsShared() = appTest("secret-typed", "chat") {
        server.stub("secret-providers", "secrets.status", "[]", 1)
        askForSecret()
        await("To open the pull request")
        screenshot("asked")
        tap("GitHub token")
        type("ghp_example")
        screenshot("typed")
        tap("Share")
        requests("secret-respond", 1)
    }

    @Test fun passwordManagerItemIsPicked() = appTest("secret-picked", "chat") {
        server.stub("secret-providers", "secrets.status", """[{"scheme":"pass","name":"Proton Pass","installed":true,"connected":true,"levels":3}]""", 1)
        server.stub("secret-browse", "secrets.browse", """[{"name":"Work"}]""", 1)
        askForSecret()
        tap("Work")
        tap("Work")
        screenshot("browsing")
        tap("Work")
        await("Change")
        screenshot("picked")
        tap("Share")
        requests("secret-respond", 1)
    }
}
