package kinuvo.harness.execution.api

import kinuvo.protocol.*
import kotlinx.coroutines.CoroutineScope

/**
 * Kinuvo's tools as an MCP server for one execution: [url] with a Session-scoped [token]. A harness without HTTP MCP runs
 * [bridge], a stdio command that proxies to [url]; none means it can't reach the tools.
 */
data class KinuvoMcp(val url: String, val token: String, val bridge: List<String>? = null)

interface ExecutionFactory {
    suspend fun probe(config: HarnessConnection): ExecutionProbe = ExecutionProbe(ExecutionReadiness(true, false, "Probe unavailable", "unsupported"))
    suspend fun open(config: HarnessConnection, registration: AgentRegistration, state: ExecutionState, broker: ExecutionBroker, scope: CoroutineScope): ExecutionSession
    suspend fun open(config: HarnessConnection, registration: AgentRegistration, state: ExecutionState, broker: ExecutionBroker, scope: CoroutineScope, tools: KinuvoMcp?): ExecutionSession =
        open(config, registration, state, broker, scope)
}
