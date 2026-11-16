package kinuvo.ui.e2e

import kinuvo.protocol.Api
import kotlin.test.Test

class WorkflowFlowTest {
    @Test fun buildsAGraphAndRunsItPassingOutputsAlong() = appTest("workflow", script = {
        on("Review: Plan v1") { reply("Looks great") }
        on("Draft the plan") { reply("Plan v1") }
    }) {
        pair()
        openHome()
        tap("Routines & Workflows")
        await("No [Ww]orkflows yet")
        tap("New [Ww]orkflow")
        await("Unsaved changes|Step step-1")
        tap("Step step-1")
        tap("What should the Agent do\\?")
        type("Draft the plan")
        tap("Agent turn")
        await("Step step-2")
        tap("What should the Agent do\\?")
        type("Review: ")
        tap("step-1 output")
        await("Unsaved changes")
        screenshot("graph")
        tap("Save")
        gone("Unsaved changes")
        tap("Run now")
        tap("Step step-2")
        eventually("Looks great")
        screenshot("run")
        requests(Api.CreateWorkflow, 1)
        requests(Api.RunWorkflow, 1)
    }

    @Test fun approvalStepWaitsForTheUser() = appTest("workflow-approval", script = {
        on("Write the draft") { reply("Draft ready") }
        on("Publish: Draft ready") { reply("Published") }
    }) {
        pair()
        openHome()
        tap("Routines & Workflows")
        tap("New [Ww]orkflow")
        tap("Step step-1")
        tap("What should the Agent do\\?")
        type("Write the draft")
        tap("Approval")
        await("Step approval-1")
        tap("Publish the draft\\?")
        type("Publish it?")
        tap("Agent turn")
        tap("What should the Agent do\\?")
        type("Publish: ")
        tap("step-1 output")
        tap("Save")
        gone("Unsaved changes")
        tap("Run now")
        tap("Step approval-1")
        await("Allow", timeout = 30_000)
        screenshot("waiting")
        tap("Allow")
        tap("Step step-2")
        eventually("Published")
    }

    @Test fun desktopOpensAWorkflowBesideTheList() = appTest("workflow-desktop", desktop = true) {
        pair()
        tap("Home")
        tap("Routines & Workflows")
        await("No [Ww]orkflows yet")
        tap("New workflow")
        // The new graph opens in the page's detail, with the tables still beside it.
        await("Step step-1")
        await("All Workflows")
        screenshot("beside-list")
    }
}

/** Scrolls to [text] until the run shows it, since step results land below the graph on narrow windows. */
private suspend fun AppDriver.eventually(text: String) {
    repeat(20) { if (!visible(text)) runCatching { scrollTo(text) } }
    await(text)
}
