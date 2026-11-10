package kinuvo.ui.e2e

import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.isNotEnabled
import kotlin.test.Test

/** External harness Sessions and attachment gating; the native pickers themselves stay in Maestro. */
class ExternalFlowTest {
    private suspend fun AppDriver.openExternalSession() {
        openPairing("123456")
        await("Agents and sessions", 20_000)
        tap("Agents and sessions")
        tap("External regression")
    }

    @Test fun externalSessionAnswersAndContinues() = mockedAppTest("external-session", "mobile-external") {
        openExternalSession()
        await("Choose a retry limit")
        await("3")
        await(label("Submit").and(isEnabled()))
        screenshot("question")
        tap("Submit")
        await("Retry limit accepted", 20_000)
        gone("Choose a retry limit")
        await("External transcript retained")
        await(label("Attach: Attachments can't be sent to this Session").and(isNotEnabled()))
        tap("Message")
        type("Continue external Session")
        tap("Send")
        await("External follow-up completed", 20_000)
        screenshot("continued")
        requestsAtLeast("mobile/external-catch-up")
        requestsAtLeast("mobile/external-capabilities")
        requests("mobile/external-answer", 1)
        requests("mobile/external-send", 1)
    }

    @Test fun lostExternalSessionKeepsItsTranscript() = mockedAppTest("external-session-lost", "mobile-external-lost") {
        openExternalSession()
        await("Start a new Session to keep chatting.")
        gone("Session ended")
        gone("Resume session")
        await("External transcript retained")
        await("External response interrupted")
        tap("External response interrupted")
        await("Unfinished external draft")
        screenshot("lost")
        requestsAtLeast("mobile/external-lost-catch-up")
        requestsAtLeast("mobile/external-lost-capabilities")
    }

    @Test fun imageModelsOfferPhotosAndFiles() = mockedAppTest("attachment-picker", "mobile-attachments") {
        pair()
        tap("Attach")
        await(label("Photos").and(isEnabled()))
        await(label("Files").and(isEnabled()))
        screenshot("attach-menu")
        requestsAtLeast("mobile/attachment-capabilities")
    }

    @Test fun externalHarnessOffersPhotosAndFiles() = mockedAppTest("external-attachments", "mobile-external-attachments") {
        pair()
        tap("Attach")
        await(label("Photos").and(isEnabled()))
        await(label("Files").and(isEnabled()))
        requestsAtLeast("mobile/external-attachment-capabilities")
    }

    @Test fun textOnlyHarnessDisablesPhotos() = mockedAppTest("external-text-attachments", "mobile-external-text") {
        pair()
        tap("Attach")
        await(label("Photos").and(isNotEnabled()))
        await(label("Files").and(isEnabled()))
        screenshot("attach-menu")
        requestsAtLeast("mobile/external-text-capabilities")
    }
}
