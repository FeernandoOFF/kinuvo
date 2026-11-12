package kinuvo.gateways.mcp

import io.ktor.http.*
import io.ktor.server.cio.CIO
import io.ktor.server.engine.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.readBuffer
import kotlinx.io.readByteArray

/** A private loopback listener for local external harnesses; never exposed by the embedded Server. */
class McpListener(access: McpAccess) {
    private val endpoint = McpEndpoint(access)
    private var engine: EmbeddedServer<*, *>? = null
    var url: String = ""; private set
    suspend fun start() {
        val server = embeddedServer(CIO, host = "127.0.0.1", port = 0) {
            routing {
                post("/mcp") {
                    call.response.header("Cache-Control", "no-store")
                    if (call.request.header("Origin") != null) {
                        call.respondText("Forbidden", status = HttpStatusCode.Forbidden); return@post
                    }
                    val bytes = call.receiveChannel().readBuffer(1_048_577L).readByteArray()
                    if (bytes.size > 1_048_576) {
                        call.respondText("Request too large", status = HttpStatusCode.PayloadTooLarge); return@post
                    }
                    val reply = endpoint.handle(call.request.header("Authorization"), bytes.decodeToString())
                    call.respondText(reply.body.orEmpty(), ContentType.Application.Json, HttpStatusCode.fromValue(reply.status))
                }
            }
        }.start(wait = false)
        engine = server
        url = "http://127.0.0.1:${server.engine.resolvedConnectors().first().port}/mcp"
    }
    fun stop() { engine?.stop(0, 1000); engine = null }
}
