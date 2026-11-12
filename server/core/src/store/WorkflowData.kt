package kinuvo.server.store

import kinuvo.protocol.workflow.*
import kotlinx.serialization.Serializable

@Serializable
data class WorkflowData(val workflows: List<Workflow> = emptyList(), val runs: List<WorkflowRun> = emptyList(),
    val next: Map<WorkflowId, Long?> = emptyMap())
