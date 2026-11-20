package kinuvo.server.store

import kinuvo.protocol.agent.AgentId
import kinuvo.protocol.session.SessionId
import kinuvo.protocol.workflow.*
import kotlinx.serialization.Serializable

@Serializable
data class WorkflowData(val workflows: List<Workflow> = emptyList(), val runs: List<WorkflowRun> = emptyList(),
    val next: Map<WorkflowId, Long?> = emptyMap(),
    /** Legacy attention Sessions, adopted on next use. */
    val sessions: Map<WorkflowId, SessionId> = emptyMap(),
    val agentSessions: Map<WorkflowId, Map<AgentId, SessionId>> = emptyMap())
