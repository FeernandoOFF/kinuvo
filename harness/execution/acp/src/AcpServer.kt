@file:OptIn(com.agentclientprotocol.annotations.UnstableApi::class)

package kinuvo.execution.acp

import com.agentclientprotocol.agent.v2.Agent
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.transport.StdioTransport
import kinuvo.execution.api.ExecutionDispatch
import kotlinx.coroutines.*
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered

suspend fun serveAcp(dispatch: ExecutionDispatch, agentId: String) = coroutineScope {
    val transport = StdioTransport(this, Dispatchers.IO, System.`in`.asSource().buffered(), System.out.asSink().buffered())
    val closed = CompletableDeferred<Unit>()
    transport.onClose { closed.complete(Unit) }
    val protocol = Protocol(this, transport)
    Agent(protocol, KinuvoAgent(dispatch, agentId))
    try { protocol.start(); closed.await() } finally { protocol.close() }
}
