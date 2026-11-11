package kinuvo.ui.e2e

import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.isNotEnabled
import kinuvo.testing.server.FakeHarness
import kinuvo.testing.server.FakeModelProvider
import kinuvo.testing.server.FakeModels
import kinuvo.protocol.Api
import kinuvo.protocol.execution.ExecutionCapabilities
import kinuvo.protocol.model.ModelInfo
import kinuvo.protocol.approval.Question
import kinuvo.protocol.approval.QuestionKind
import kotlin.test.Test
import kotlin.test.assertEquals

/** External harness Sessions and attachment gating; the native pickers themselves stay in Maestro. */
class ExternalFlowTest {
    private val retryLimit = Question("attempts", "Retry limit", QuestionKind.Number, required = true, default = listOf("3"))

    private val harness = FakeHarness("e2e-acp", "E2E host", ExecutionCapabilities(cancel = true, questions = true)) { prompt ->
        if (prompt == "Start") {
            say("External transcript retained")
            askQuestions("Choose a retry limit", retryLimit)
            reply("Retry limit accepted")
        } else reply("External follow-up completed")
    }

    private suspend fun AppDriver.openExternalSession() {
        openPairing(pairingCode)
        await("Agents and sessions", 20_000)
        tap("Agents and sessions")
        tap("External regression")
    }

    @Test fun externalSessionAnswersAndContinues() = appTest("external-session", harnesses = listOf(harness), scenario = {
        withoutAgent(it)
        externalAgent(harness, "e2e", "E2E Agent")
    }) {
        server.startSession("e2e", prompt = "Start", title = "External regression")
        openExternalSession()
        await("Choose a retry limit")
        await("3")
        await(label("Submit").and(isEnabled()))
        screenshot("question")
        tap("Submit")
        await("Retry limit accepted", 20_000)
        gone("Choose a retry limit")
        await("External transcript retained")
        await(label("Attach.*").and(isNotEnabled()))
        tap("Message")
        type("Continue external Session")
        tap("Send")
        await("External follow-up completed", 20_000)
        screenshot("continued")
        requestsAtLeast(Api.CatchUp)
        requestsAtLeast(Api.Capabilities)
        requests(Api.Respond, 1)
        requests(Api.Send, 1)
        assertEquals(listOf("Start", "Continue external Session"), harness.sessions.single().prompts.map { it.text })
    }

    @Test fun lostExternalSessionKeepsItsTranscript() = appTest("external-session-lost", harnesses = listOf(harness), scenario = {
        withoutAgent(it)
        externalAgent(harness, "e2e", "E2E Agent")
        lostExternalSession("e2e", title = "External regression", draft = "Unfinished external draft") {
            user("Start")
            assistant("External transcript retained")
        }
    }) {
        openExternalSession()
        await("Start a new Session to keep chatting.")
        gone("Session ended")
        gone("Resume session")
        await("External transcript retained")
        await("Execution ended before this output could be confirmed.")
        tap("Execution ended before this output could be confirmed.")
        await("Unfinished external draft")
        screenshot("lost")
        requestsAtLeast(Api.CatchUp)
        requestsAtLeast(Api.Capabilities)
    }

    @Test fun imageModelsOfferPhotosAndFiles() = appTest("attachment-picker",
        plugins = listOf(FakeModels(FakeModelProvider("vision", "Vision models", listOf(ModelInfo("vision-1", input = listOf("text", "image", "file")))))),
        scenario = { withoutAgent(it); agent("e2e", "E2E Agent") { c -> c.copy(provider = "vision", model = "vision-1") } },
    ) {
        pair()
        tap("Attach")
        await(label("Photos").and(isEnabled()))
        await(label("Files").and(isEnabled()))
        screenshot("attach-menu")
        requestsAtLeast(Api.Capabilities)
    }

    @Test fun externalHarnessOffersPhotosAndFiles() = attachments("external-attachments", setOf("text/plain", "image/png", "image/jpeg")) {
        await(label("Photos").and(isEnabled()))
        await(label("Files").and(isEnabled()))
    }

    @Test fun textOnlyHarnessDisablesPhotos() = attachments("external-text-attachments", setOf("text/plain")) {
        await(label("Photos").and(isNotEnabled()))
        await(label("Files").and(isEnabled()))
        screenshot("attach-menu")
    }

    /** A new external Session knows what its harness takes only once it is open, so these start from a saved one. */
    private fun attachments(flow: String, mimeTypes: Set<String>, check: suspend AppTest.() -> Unit) {
        val attaching = FakeHarness("e2e-acp", "E2E host", ExecutionCapabilities(cancel = true, questions = true, supportedMimeTypes = mimeTypes))
        appTest(flow, harnesses = listOf(attaching), scenario = {
            withoutAgent(it)
            externalAgent(attaching, "e2e", "E2E Agent")
        }) {
            server.startSession("e2e", prompt = "Hello", title = "External regression")
            openExternalSession()
            tap("Attach")
            check()
            requestsAtLeast(Api.Capabilities)
        }
    }
}
