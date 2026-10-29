package kinuvo.cli

import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import kinuvo.execution.api.NativeExecutionDispatch
import kinuvo.protocol.*

internal class AcpCommand : ServerCommand("acp", "Expose one native Agent over ACP v2 stdio through the running Server.") {
    private val selected by option("--agent", help = "Existing native Agent ID").required()
    override suspend fun run() = session.call(running = true) {
        require(client.call(Api.Agents, Unit).any { it.id == selected && it.executionKind == ExecutionKind.Native }) { "Select an existing native Agent" }
        val dispatch = client.acpDispatch()
        kinuvo.execution.acp.serveAcp(dispatch, selected)
    }
}

internal fun kinuvo.client.KinuvoClient.acpDispatch() = NativeExecutionDispatch(
    { call(Api.CreateSession, AgentRef(it)) },
    { _, id -> call(Api.ExecutionSnapshot, ExecutionRef(id)) },
    { call(Api.ExecutionAnswer, it) },
    { call(Api.Send, it) },
    { call(Api.Stop, StopParams(it)) },
)
