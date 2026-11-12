package kinuvo.gateways.acp

import kinuvo.client.KinuvoClient
import kinuvo.harness.ExecutionDispatch
import kinuvo.protocol.*

fun KinuvoClient.acpDispatch(): ExecutionDispatch = ClientDispatch(this)

private class ClientDispatch(private val client: KinuvoClient) : ExecutionDispatch {
    override suspend fun create(agentId: String) = client.call(Api.CreateSession, AgentRef(agentId))
    override suspend fun snapshot(agentId: String, sessionId: String) = client.call(Api.ExecutionSnapshot, ExecutionRef(sessionId))
    override suspend fun answer(answer: RespondParams) = client.call(Api.Respond, answer)
    override suspend fun send(params: SendParams) = client.call(Api.Send, params)
    override suspend fun stop(sessionId: String) = client.call(Api.Stop, StopParams(sessionId))
}
