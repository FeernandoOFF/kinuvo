package kinuvo.harness.server.testing

import kinuvo.harness.execution.api.ExecutionBroker
import kinuvo.harness.execution.api.ExecutionFactory
import kinuvo.harness.execution.api.ExecutionAcceptance
import kinuvo.harness.execution.api.ExecutionInput
import kinuvo.harness.execution.api.ExecutionSession
import kinuvo.harness.execution.api.ExecutionState
import kinuvo.protocol.AgentRegistration
import kinuvo.protocol.HarnessConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** Every prompt the built-in harness was given, oldest first. */
class PromptLog {
    private val seen = MutableStateFlow(emptyList<ExecutionInput>())

    val inputs: List<ExecutionInput> get() = seen.value

    internal fun add(input: ExecutionInput) = seen.update { it + input }
}

internal fun ExecutionFactory.recording(log: PromptLog): ExecutionFactory {
    val factory = this
    return object : ExecutionFactory {
        override suspend fun open(config: HarnessConnection, registration: AgentRegistration, state: ExecutionState, broker: ExecutionBroker, scope: CoroutineScope): ExecutionSession {
            val session = factory.open(config, registration, state, broker, scope)
            return object : ExecutionSession by session {
                override suspend fun prompt(input: ExecutionInput): ExecutionAcceptance { log.add(input); return session.prompt(input) }
            }
        }
    }
}
