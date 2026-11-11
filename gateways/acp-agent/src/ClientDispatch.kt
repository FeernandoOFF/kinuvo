package kinuvo.gateways.acp

import kinuvo.client.KinuvoClient
import kinuvo.harness.ExecutionDispatch
import kinuvo.protocol.*
import kinuvo.protocol.agent.*
import kinuvo.protocol.approval.*
import kinuvo.protocol.execution.*
import kinuvo.protocol.session.*
fun KinuvoClient.acpDispatch(): ExecutionDispatch = ClientDispatch(this)

private class ClientDispatch(private val client: KinuvoClient) : ExecutionDispatch {
    override suspend fun create(agentId: AgentId) = client.call(Api.CreateSession, AgentRef(agentId))
    override suspend fun snapshot(agentId: AgentId, sessionId: SessionId) = client.call(Api.ExecutionSnapshot, ExecutionRef(sessionId.value))
    override suspend fun answer(answer: RespondParams) = client.call(Api.Respond, answer)
    override suspend fun send(params: SendParams) = client.call(Api.Send, params)
    override suspend fun stop(sessionId: SessionId) = client.call(Api.Stop, StopParams(sessionId))
}
