package kinuvo.cli

import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import kinuvo.gateways.acp.acpDispatch
import kinuvo.gateways.acp.serveAcp
import kinuvo.protocol.*
import kinuvo.protocol.execution.*
import kinuvo.protocol.agent.AgentId
internal class AcpCommand : ServerCommand("acp", "Expose one native Agent over ACP v2 stdio through the running Server.") {
    private val selected by option("--agent", help = "Existing native Agent ID").required()
    override suspend fun run() = session.call(running = true) {
        require(client.call(Api.Agents, Unit).any { it.id.value == selected && it.executionKind == ExecutionKind.Native }) { "Select an existing native Agent" }
        serveAcp(client.acpDispatch(), AgentId(selected))
    }
}
