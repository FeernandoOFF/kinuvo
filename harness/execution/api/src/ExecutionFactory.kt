package kinuvo.execution.api

import kinuvo.protocol.*
import kotlinx.coroutines.CoroutineScope

interface ExecutionFactory {
    suspend fun probe(config: HarnessConnection): ExecutionProbe = ExecutionProbe(ExecutionReadiness(true, false, "Probe unavailable", "unsupported"))
    suspend fun open(config: HarnessConnection, registration: AgentRegistration, state: ExecutionState, broker: ExecutionBroker, scope: CoroutineScope): ExecutionSession
}
