package kinuvo.testing.server

import kinuvo.harness.ExecutionBroker
import kinuvo.harness.ExecutionFactory
import kinuvo.harness.ExecutionAcceptance
import kinuvo.harness.ExecutionInput
import kinuvo.harness.ExecutionSession
import kinuvo.harness.ExecutionState
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
