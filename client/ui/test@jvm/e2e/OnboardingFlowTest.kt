package kinuvo.ui.e2e

import kinuvo.protocol.Api
import kotlin.test.Test

class OnboardingFlowTest {
    @Test fun emptyAgentsShowAnErrorAndAllowRetry() = appTest("onboarding-empty", desktop = true, onboarding = true,
        scenario = { agent -> withoutAgent(agent) }) {
        val error = "This Server returned no Agents. Check its Agents folder and access permissions, then try again."
        tap("Set up on this device")
        await(error)
        tap("Set up on this device")
        await(error)
        gone("Starting…")
        requestsAtLeast(Api.Agents, 2)
        screenshot("retry")
    }

    @Test fun existingAgentAdvancesToModelSetup() = appTest("onboarding-agent", desktop = true, onboarding = true) {
        await("Set up on this device")
        tap("Set up on this device")
        await(Regex.escape("Which mind should I use?"))
        screenshot("model")
    }
}
