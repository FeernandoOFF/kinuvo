package kinuvo.cli

import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import kinuvo.harness.ExecutionDispatch
import kinuvo.protocol.*

internal class AcpCommand : ServerCommand("acp", "Expose one native Agent over ACP v2 stdio through the running Server.") {
    private val selected by option("--agent", help = "Existing native Agent ID").required()
    override suspend fun run() = session.call(running = true) {
        require(client.call(Api.Agents, Unit).any { it.id == selected && it.executionKind == ExecutionKind.Native }) { "Select an existing native Agent" }
        val dispatch = client.acpDispatch()
        kinuvo.harness.acp.serveAcp(dispatch, selected)
    }
}

internal fun kinuvo.client.KinuvoClient.acpDispatch(): ExecutionDispatch = ClientDispatch(this)

private class ClientDispatch(private val client: kinuvo.client.KinuvoClient) : ExecutionDispatch {
    override suspend fun create(agentId: String) = client.call(Api.CreateSession, AgentRef(agentId))
    override suspend fun snapshot(agentId: String, sessionId: String) = client.call(Api.ExecutionSnapshot, ExecutionRef(sessionId))
    override suspend fun answer(answer: RespondParams) = client.call(Api.Respond, answer)
    override suspend fun send(params: SendParams) = client.call(Api.Send, params)
    override suspend fun stop(sessionId: String) = client.call(Api.Stop, StopParams(sessionId))
}
