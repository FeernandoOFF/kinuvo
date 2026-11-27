package kinuvo.cli

import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.int
import kinuvo.gateways.agui.serveAgUi
import kinuvo.protocol.*
import kinuvo.protocol.agent.AgentId

internal class AgUiCommand : ServerCommand("agui", "Serve one Agent to AG-UI clients over HTTP and SSE through the running Server (draft).") {
    private val selected by option("--agent", help = "Existing Agent ID").required()
    private val host by option("--host", help = "Loopback address to listen on").default("127.0.0.1")
    private val port by option("--port", help = "Port to listen on").int().default(8790)
    private val allowedOrigins by option("--allow-origin", help = "Browser origin allowed to call the gateway, e.g. http://localhost:3000; repeatable, none by default").multiple()
    override suspend fun run() = session.call(running = true) {
        require(client.call(Api.Agents, Unit).any { it.id.value == selected }) { "Select an existing Agent" }
        println("AG-UI gateway on http://$host:$port/")
        serveAgUi(client, AgentId(selected), host, port, allowedOrigins.toSet())
    }
}
