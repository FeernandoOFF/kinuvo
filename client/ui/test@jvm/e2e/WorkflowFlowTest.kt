package kinuvo.ui.e2e

import kinuvo.protocol.Api
import kinuvo.protocol.agent.AgentId
import kinuvo.protocol.workflow.*
import kotlin.test.Test

/** The graph itself is a web page, which these flows cannot drive; they cover the native list, settings, Runs as, Run and history. */
class WorkflowFlowTest {
    private val agent = AgentId("e2e")
    private fun draftThenReview(vararg extra: WorkflowStep, edges: List<WorkflowEdge>) = Workflow(WorkflowId("release"), "Release notes", listOf(
        WorkflowStep.AgentTurn(StepId("step-1"), agent, "Draft the plan"), *extra), edges, agentId = agent, createdAt = 1)

    private suspend fun AppTest.seed(workflow: Workflow) {
        server.client(token = server.auth.sessionToken()).also { it.connect() }.call(Api.CreateWorkflow, workflow)
    }

    @Test fun runsASavedWorkflowAndShowsItInHistory() = appTest("workflow", script = {
        on("Review: Plan v1") { reply("Looks great") }
        on("Draft the plan") { reply("Plan v1") }
    }) {
        seed(draftThenReview(WorkflowStep.AgentTurn(StepId("step-2"), agent, "Review: {{steps.step-1.output}}"),
            edges = listOf(WorkflowEdge(StepId("step-1"), StepId("step-2")))))
        pair()
        openHome()
        tap("Routines & Workflows")
        tap("Release notes")
        tap("Settings and runs")
        await("Runs as")
        scrollTo("No runs yet.*")
        screenshot("settings")
        tap("Run now")
        eventually("Done")
        gone("No runs yet.*")
        screenshot("run")
        requests(Api.RunWorkflow, 1)
    }

    @Test fun approvalStepWaitsForTheUser() = appTest("workflow-approval", script = {
        on("Write the draft") { reply("Draft ready") }
        on("Publish: Draft ready") { reply("Published") }
    }) {
        seed(Workflow(WorkflowId("publish"), "Publish flow", listOf(
            WorkflowStep.AgentTurn(StepId("step-1"), agent, "Write the draft"),
            WorkflowStep.Approval(StepId("approval-1"), "Publish it?"),
            WorkflowStep.AgentTurn(StepId("step-2"), agent, "Publish: {{steps.step-1.output}}"),
        ), listOf(WorkflowEdge(StepId("step-1"), StepId("approval-1")), WorkflowEdge(StepId("approval-1"), StepId("step-2"))), agentId = agent, createdAt = 1))
        pair()
        openHome()
        tap("Routines & Workflows")
        tap("Publish flow")
        tap("Settings and runs")
        tap("Run now")
        await("Allow", timeout = 30_000)
        screenshot("waiting")
        tap("Allow")
        eventually("Done")
        gone("Allow")
    }

    @Test fun nameAndRunsAsAreEditedNativelyAndSaved() = appTest("workflow-settings") {
        pair()
        openHome()
        tap("Routines & Workflows")
        await("No [Ww]orkflows yet")
        tap("New [Ww]orkflow")
        await("Runs as")
        tapBelow("New Workflow", "Name")
        type(" v2")
        await("Unsaved changes")
        tap("Not set")
        tap("E2E Agent")
        screenshot("edited")
        tap("Save")
        gone("Unsaved changes")
        requests(Api.CreateWorkflow, 1)
        requests(Api.UpdateWorkflow, 1)
    }

    @Test fun aNewWorkflowStartsFromAnExample() = appTest("workflow-template", desktop = true) {
        pair()
        tap("Home")
        tap("Routines & Workflows")
        tap("New workflow")
        await("Start from an example")
        screenshot("examples")
        tap("Triage with a condition")
        await("Unsaved changes")
        gone("Start from an example")
        tap("Save")
        gone("Unsaved changes")
        scrollTo("Run with these")
        screenshot("inputs")
        requests(Api.UpdateWorkflow, 1)
    }

    @Test fun aRunTakesItsInputsAndShowsItsResult() = appTest("workflow-input", script = {
        on("Write about dogs") { reply("Dogs are loyal") }
        on("Write about cats") { reply("Cats nap") }
    }) {
        seed(Workflow(WorkflowId("essay"), "Essay", listOf(WorkflowStep.AgentTurn(StepId("agent-1"), agent, "Write about {{input.topic}}")),
            agentId = agent, createdAt = 1, inputs = listOf(WorkflowInput("topic", "What to write about", "cats"))))
        pair()
        openHome()
        tap("Routines & Workflows")
        tap("Essay")
        tap("Settings and runs")
        await("Runs as")
        scrollTo("Run with these")
        tapAbove("cats", "Run with these")
        clear()
        type("dogs")
        tap("Run with these")
        eventually("Dogs are loyal")
        screenshot("result")
    }

    @Test fun aReviewWaitingOnTheUserIsOfferedFromTheScreen() = appTest("workflow-review") {
        seed(Workflow(WorkflowId("check"), "Check flow", listOf(WorkflowStep.Review(StepId("review-1"), "Check the fields", "{\"name\":\"Dana\"}")), agentId = agent, createdAt = 1))
        pair()
        openHome()
        tap("Routines & Workflows")
        tap("Check flow")
        tap("Run now")
        await("Review: Check the fields", timeout = 30_000)
        screenshot("waiting")
        tap("Review")
        await("Dana", timeout = 30_000)
        // The run's own lines are dividers in the Workflow's Session.
        await("Run started")
        screenshot("session")
    }

    @Test fun desktopOpensAWorkflowBesideTheList() = appTest("workflow-desktop", desktop = true) {
        pair()
        tap("Home")
        tap("Routines & Workflows")
        await("No [Ww]orkflows yet")
        tap("New workflow")
        // The new workflow opens in the page's detail, with the tables still beside it.
        await("Runs as")
        await("All Workflows")
        screenshot("beside-list")
    }
}

/** Scrolls the settings pane to [text] until the run lands, since the run history sits below the fold on narrow windows. */
private suspend fun AppDriver.eventually(text: String) {
    repeat(20) { if (!visible(text)) runCatching { scrollTo(text) } }
    await(text, timeout = 30_000)
}
