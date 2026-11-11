package kinuvo.testing.server

import kinuvo.harness.ExecutionBroker
import kinuvo.harness.NativeHarness
import kinuvo.harness.ExecutionAcceptance
import kinuvo.harness.ExecutionInput
import kinuvo.harness.ExecutionSession
import kinuvo.harness.ExecutionState
import kinuvo.protocol.execution.AgentRegistration
import kinuvo.protocol.execution.HarnessConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** Every prompt the built-in harness was given, oldest first. */
class PromptLog {
    private val seen = MutableStateFlow(emptyList<ExecutionInput>())

    val inputs: List<ExecutionInput> get() = seen.value

    internal fun add(input: ExecutionInput) = seen.update { it + input }
}

internal fun NativeHarness.recording(log: PromptLog): NativeHarness {
    val factory = this
    return object : NativeHarness by factory {
        override suspend fun open(config: HarnessConnection, registration: AgentRegistration, state: ExecutionState, broker: ExecutionBroker, scope: CoroutineScope): ExecutionSession {
            val session = factory.open(config, registration, state, broker, scope)
            return object : ExecutionSession by session {
                override suspend fun prompt(input: ExecutionInput): ExecutionAcceptance { log.add(input); return session.prompt(input) }
            }
        }
    }
}
