package kinuvo.harness.builtin

import ai.koog.agents.core.agent.GraphAIAgent
import kinuvo.protocol.usage.RequestKind
import kotlinx.io.files.Path

interface AgentTracing : AutoCloseable {
    fun install(context: GraphAIAgent.FeatureContext, agentId: String, sessionId: String, runId: String, kind: RequestKind)
}

expect fun localTracing(traces: Path): AgentTracing
