package kinuvo.harness.builtin

import ai.koog.agents.core.agent.GraphAIAgent
import kinuvo.protocol.RequestKind
import kotlinx.io.files.Path

actual fun localTracing(traces: Path): AgentTracing = object : AgentTracing {
    override fun install(context: GraphAIAgent.FeatureContext, agentId: String, sessionId: String, runId: String, kind: RequestKind) {}
    override fun close() {}
}
