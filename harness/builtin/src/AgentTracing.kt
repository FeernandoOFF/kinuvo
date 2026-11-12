package kinuvo.harness.builtin

import ai.koog.agents.core.agent.GraphAIAgent
import kinuvo.protocol.usage.RequestKind
import kinuvo.protocol.agent.AgentId
import kinuvo.protocol.session.SessionId
import kinuvo.protocol.session.RunId
import kotlinx.io.files.Path

interface AgentTracing : AutoCloseable {
    fun install(context: GraphAIAgent.FeatureContext, agentId: AgentId, sessionId: SessionId, runId: RunId, kind: RequestKind)
}

expect fun localTracing(traces: Path): AgentTracing
