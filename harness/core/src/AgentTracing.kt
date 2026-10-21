package kinuvo.harness.core

import ai.koog.agents.core.agent.GraphAIAgent
import kinuvo.harness.core.store.AgentFiles
import kinuvo.protocol.RequestKind

interface AgentTracing : AutoCloseable {
    fun install(context: GraphAIAgent.FeatureContext, agentId: String, sessionId: String, runId: String, kind: RequestKind)
}

expect fun localTracing(files: AgentFiles): AgentTracing
