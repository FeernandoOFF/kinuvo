package kinuvo.cli

import com.github.ajalt.clikt.command.CoreSuspendingCliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import java.io.BufferedReader
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path

/**
 * Started by External Agents whose harness can't reach HTTP MCP servers: proxies their stdio MCP to the Server's `/mcp`.
 * The Session-scoped token comes from `KINUVO_MCP_TOKEN`.
 */
internal class McpBridgeCommand : CoreSuspendingCliktCommand("mcp-bridge") {
    private val url by option("--url", help = "The Server's MCP endpoint").required()

    override val hiddenFromHelp = true

    override fun help(context: Context) = "Proxy stdio MCP to the Server's HTTP MCP endpoint."

    override suspend fun run() {
        val token = System.getenv(TOKEN) ?: throw UsageError("$TOKEN is not set")
        val http = HttpClient.newHttpClient()
        val output = System.out
        bridge(System.`in`.bufferedReader(), { line -> synchronized(output) { output.println(line); output.flush() } }) { body ->
            val request = HttpRequest.newBuilder(URI(url)).header("Authorization", "Bearer $token").header("Content-Type", "application/json")
                .header("Accept", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build()
            val response = http.send(request, HttpResponse.BodyHandlers.ofString())
            when (response.statusCode()) {
                200 -> response.body()
                202 -> null
                else -> throw IOException("Kinuvo answered ${response.statusCode()}")
            }
        }
    }

    companion object {
        const val TOKEN = "KINUVO_MCP_TOKEN"

        /** This JVM, to run `mcp-bridge` again. */
        fun command() = listOf(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp", System.getProperty("java.class.path"), "kinuvo.cli.MainKt", "mcp-bridge")
    }
}

/** Sends each line of [input] through [post] concurrently, since a call can wait on an Approval; replies go to [output]. */
internal suspend fun bridge(input: BufferedReader, output: (String) -> Unit, post: (String) -> String?) = coroutineScope {
    for (line in input.lineSequence()) {
        if (line.isBlank()) continue
        launch(Dispatchers.IO) {
            val reply = try { post(line) } catch (e: IOException) { failure(line, e.message) }
            reply?.let { output(it.replace("\n", " ")) }
        }
    }
}

private fun failure(request: String, message: String?): String? {
    val id = runCatching { Json.parseToJsonElement(request).jsonObject["id"] }.getOrNull() ?: return null
    return buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        putJsonObject("error") { put("code", -32603); put("message", message ?: "Kinuvo isn't reachable") }
    }.toString()
}
