package kinuvo.server.rpc

import kinuvo.protocol.Api

internal fun RouteBuilder.workflowRoutes() {
    handle(Api.Workflows) { server.workflows.list() }
    handle(Api.Workflow) { server.workflows.get(it) }
    handle(Api.CreateWorkflow) { server.workflows.save(it, create = true) }
    handle(Api.UpdateWorkflow) { server.workflows.save(it, create = false) }
    handle(Api.DeleteWorkflow) { server.workflows.delete(it) }
    handle(Api.RunWorkflow) { server.workflows.run(it) }
    handle(Api.CancelWorkflow) { server.workflows.cancel(it) }
    handle(Api.WorkflowRuns) { server.workflows.history(it) }
}
