package kinuvo.server.store

import kinuvo.protocol.session.SessionId
import kinuvo.protocol.workflow.*
import kotlinx.serialization.Serializable

@Serializable
data class WorkflowData(val workflows: List<Workflow> = emptyList(), val runs: List<WorkflowRun> = emptyList(),
    val next: Map<WorkflowId, Long?> = emptyMap(),
    /** The Session each Workflow asks for the user in, kept across its runs. */
    val sessions: Map<WorkflowId, SessionId> = emptyMap())
