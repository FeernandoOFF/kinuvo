package kinuvo.harness.core

import ai.koog.agents.core.agent.GraphAIAgent
import kinuvo.harness.core.store.AgentFiles
import kinuvo.protocol.RequestKind

actual fun localTracing(files: AgentFiles): AgentTracing = object : AgentTracing {
    override fun install(context: GraphAIAgent.FeatureContext, agentId: String, sessionId: String, runId: String, kind: RequestKind) {}
    override fun close() {}
}
