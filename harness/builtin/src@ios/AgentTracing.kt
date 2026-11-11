package kinuvo.harness.builtin

import ai.koog.agents.core.agent.GraphAIAgent
import kinuvo.protocol.usage.RequestKind
import kinuvo.protocol.agent.AgentId
import kinuvo.protocol.session.SessionId
import kinuvo.protocol.session.RunId
import kotlinx.io.files.Path

actual fun localTracing(traces: Path): AgentTracing = object : AgentTracing {
    override fun install(context: GraphAIAgent.FeatureContext, agentId: AgentId, sessionId: SessionId, runId: RunId, kind: RequestKind) {}
    override fun close() {}
}
