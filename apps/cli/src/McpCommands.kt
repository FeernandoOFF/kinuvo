package kinuvo.cli

import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import kinuvo.client.LoopbackCallback
import kinuvo.client.oauthState
import kinuvo.protocol.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlin.concurrent.thread

internal class McpCommands : Group(
    "mcp", "Manage MCP servers: the Agent's own (default) or shared by every Agent (--shared).",
    ListMcp(), AddMcp(), RemoveMcp(), TestMcp(), LoginMcp(), LogoutMcp(),
) {
    override fun aliases() = mapOf("ls" to listOf("list"), "rm" to listOf("remove"))
}

private class ListMcp : ServerCommand("list", "List the MCP servers the Agent can use.") {
    override suspend fun run() {
        val servers = session.call { client.call(Api.McpServers, AgentRef(agentId())) }
        echo(table(listOf("NAME", "SCOPE", "TYPE", "AUTH", "TARGET"), servers.map { s ->
            listOf(
                s.name, s.scope.name.lowercase() + if (s.active) "" else " (overridden)",
                if (s.command != null) "stdio" else "http",
                when (s.auth) {
                    McpAuthStatus.None -> "-"
                    McpAuthStatus.Required -> "needs login"
                    McpAuthStatus.SignedIn -> "signed in"
                },
                s.command?.let { (listOf(it) + s.args).joinToString(" ") } ?: s.url.orEmpty(),
            )
        }))
    }
}

private class AddMcp : ServerCommand("add", "Add or replace an MCP server; put the command's arguments after --.") {
    private val name by argument("NAME")
    private val url by option("--url", help = "Streamable HTTP endpoint")
    private val command by option("--command", help = "Command that starts a stdio server")
    private val args by argument("ARGS", help = "Arguments for --command").multiple()
    private val env by option("--env", metavar = "KEY=VALUE", help = "Environment for --command; values may use \${VAR}").multiple()
    private val headers by option("--header", metavar = "KEY=VALUE", help = "HTTP header for --url; values may use \${VAR}").multiple()
    private val include by option("--include", metavar = "TOOL", help = "Only offer these tools").multiple()
    private val exclude by option("--exclude", metavar = "TOOL", help = "Never offer these tools").multiple()
    private val approval by option("--approval", help = "auto: never ask; ask: always ask (default: by each tool's read-only hint)").choice("auto", "ask")
    private val clientId by option("--client-id", help = "Pre-registered OAuth client, for --url servers that don't allow dynamic registration")
    private val clientSecret by option("--client-secret", help = "Its secret, if any; may use \${VAR}")
    private val scopes by option("--oauth-scope", metavar = "SCOPE", help = "OAuth scope to ask for").multiple()
    private val shared by option("--shared", help = "Share it with every Agent").flag()

    override suspend fun run() {
        if ((url == null) == (command == null)) throw CliktError("Give either --url or --command")
        if (clientId == null && (clientSecret != null || scopes.isNotEmpty())) throw CliktError("--client-secret and --oauth-scope need --client-id")
        val server = McpServerInfo(
            name, scope(shared), command, args, pairs(env, "--env"), url, pairs(headers, "--header"),
            include.takeIf { it.isNotEmpty() }, exclude, approval, clientId?.let { McpOAuthClient(it, clientSecret, scopes) },
        )
        session.call { client.call(Api.SaveMcp, SaveMcpParams(agentId(), server)) }
        echo("Saved MCP server '$name'. Test it with: kinuvo mcp test $name")
    }
}

private class RemoveMcp : ServerCommand("remove", "Remove an MCP server.") {
    private val name by argument("NAME")
    private val shared by option("--shared", help = "Remove the shared one").flag()

    override suspend fun run() {
        session.call { client.call(Api.RemoveMcp, McpParams(agentId(), name, ConfigScope.Shared.takeIf { shared })) }
        echo("Removed MCP server '$name'.")
    }
}

private class TestMcp : ServerCommand("test", "Connect to an MCP server and list its tools.") {
    private val name by argument("NAME")

    override suspend fun run() {
        val result = session.call { client.call(Api.TestMcp, McpParams(agentId(), name)) }
        if (result.needsLogin) throw CliktError("'$name' needs sign-in: kinuvo mcp login $name")
        result.error?.let { throw CliktError("Couldn't connect to '$name': $it") }
        echo("'$name' offers ${result.tools.size} tool(s):\n" + result.tools.joinToString("\n") { "  $it" })
    }
}

private class LoginMcp : ServerCommand("login", "Sign in to an HTTP MCP server through OAuth in a browser.") {
    private val name by argument("NAME")
    private val shared by option("--shared", help = "Sign in to the shared one").flag()
    private val noBrowser by option("--no-browser", help = "Only print the URL to open").flag()

    // One call, so a Server started just for this command keeps the sign-in it began until it completes.
    override suspend fun run() = session.call {
        val login = client.call(Api.LoginMcp, McpLoginParams(agentId(), name, ConfigScope.Shared.takeIf { shared }))
        val callback = LoopbackCallback.start(oauthState(login.url))
        try {
            echo("Open this URL to sign in:\n\n  ${login.url}\n")
            if (!noBrowser) openBrowser(login.url)
            echo(
                if (callback != null) "Waiting for the browser. If it can't reach this machine (e.g. over SSH), paste the URL it ended on:"
                else "Port ${McpRedirect.LOOPBACK_PORT} is busy; paste the URL the browser ended on:"
            )
            // A daemon thread, since a blocked read can't be cancelled once the browser returns.
            val pasted = CompletableDeferred<String?>()
            thread(isDaemon = true) { pasted.complete(generateSequence(::readlnOrNull).map { it.trim() }.firstOrNull { it.isNotEmpty() }) }
            val url = coroutineScope {
                val returned = callback?.let { async { it.await() } }
                select {
                    pasted.onAwait { it }
                    returned?.onAwait { it }
                }.also { returned?.cancel() }
            } ?: throw CliktError("No URL given")
            val server = client.call(Api.CompleteMcpLogin, McpLoginCompleteParams(url))
            echo("Signed in to '${server.name}'.")
        } finally {
            callback?.close()
        }
    }

    private fun openBrowser(url: String) {
        val os = System.getProperty("os.name").lowercase()
        val command = when {
            "mac" in os -> listOf("open", url)
            "win" in os -> listOf("rundll32", "url.dll,FileProtocolHandler", url)
            else -> listOf("xdg-open", url)
        }
        runCatching { ProcessBuilder(command).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start() }
    }
}

private class LogoutMcp : ServerCommand("logout", "Forget the OAuth sign-in to an MCP server.") {
    private val name by argument("NAME")
    private val shared by option("--shared", help = "Sign out of the shared one").flag()

    override suspend fun run() {
        session.call { client.call(Api.LogoutMcp, McpParams(agentId(), name, ConfigScope.Shared.takeIf { shared })) }
        echo("Signed out of '$name'.")
    }
}
