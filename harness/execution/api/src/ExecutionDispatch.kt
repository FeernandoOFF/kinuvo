package kinuvo.harness.execution.api

import kinuvo.protocol.*

/** One way to run any Agent: the Server implements it over its built-in and external harnesses; a client-backed one serves gateways. */
interface ExecutionDispatch {
    suspend fun create(agentId: String): SessionInfo
    suspend fun snapshot(agentId: String, sessionId: String): ExecutionSnapshot
    suspend fun answer(answer: RespondParams): Boolean
    suspend fun send(params: SendParams): SendResult
    suspend fun stop(sessionId: String)
}
