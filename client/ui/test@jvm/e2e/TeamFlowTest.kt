package kinuvo.ui.e2e

import kotlin.test.Test

class TeamFlowTest {
    @Test fun teamRoutesToTheDefaultAndMentionedMembers() = appTest("team", script = {
        // Reviewer's turn also carries the earlier message as context, so the narrower rule goes first.
        on("check the plan") { reply("Plan looks good") }
        on("Plan the launch") { reply("Plan ready") }
    }, scenario = { agent("reviewer", "Reviewer") }) {
        pair()
        openHome()
        tap("New Team")
        tap("Name")
        type("Launch")
        tap("E2E Agent")
        tap("Reviewer")
        screenshot("new-team")
        tap("Create Team")
        tap("Message Launch")
        type("Plan the launch")
        tap("Send")
        await("Plan ready")
        tap("@Reviewer")
        type("check the plan")
        tap("Send")
        await("Plan looks good")
        screenshot("team")
    }
}
