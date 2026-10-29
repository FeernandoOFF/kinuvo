package kinuvo.execution.api

import kinuvo.protocol.*

/** Server orchestration boundary shared by native Runs and external Session adapters. */
interface ExecutionDispatch {
    suspend fun create(agentId: String): SessionInfo
    suspend fun snapshot(agentId: String, sessionId: String): ExecutionSnapshot
    suspend fun answer(answer: ExecutionAnswer): Boolean
    suspend fun send(params: SendParams): SendResult
    suspend fun stop(sessionId: String)
}

class NativeExecutionDispatch(
    private val createNative: suspend (String) -> SessionInfo,
    private val snapshotNative: suspend (String, String) -> ExecutionSnapshot,
    private val answerNative: suspend (ExecutionAnswer) -> Boolean,
    private val sendNative: suspend (SendParams) -> SendResult,
    private val stopNative: suspend (String) -> Unit,
) : ExecutionDispatch {
    override suspend fun create(agentId: String) = createNative(agentId)
    override suspend fun snapshot(agentId: String, sessionId: String) = snapshotNative(agentId, sessionId)
    override suspend fun answer(answer: ExecutionAnswer) = answerNative(answer)
    override suspend fun send(params: SendParams) = sendNative(params)
    override suspend fun stop(sessionId: String) = stopNative(sessionId)
}
