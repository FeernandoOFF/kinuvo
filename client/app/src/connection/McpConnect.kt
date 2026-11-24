package kinuvo.app.connection

import kinuvo.app.agent.ConfigRepository
import kinuvo.protocol.agent.AgentId
import kinuvo.protocol.agent.ConfigScope
import kinuvo.protocol.mcp.McpLoginParams

/** What a Connect card shows while its MCP sign-in runs. */
sealed interface ConnectState {
    /** The page is open; [url] lets the user open it again. */
    data class SigningIn(val url: String) : ConnectState
    /** The browser couldn't come back with the callback; waiting for the address it ended on. */
    data class AwaitingPaste(val url: String) : ConnectState
    data class Failed(val url: String?, val message: String) : ConnectState
}

/**
 * Runs an MCP server's OAuth sign-in through [browser]: `mcp.login`, then the callback [browser] returns or, failing
 * that, the one [paste] later provides, then `mcp.loginComplete`. Mirrors [ProviderSignIn]'s shape for MCP's RPCs.
 */
internal suspend fun runMcpSignIn(
    config: ConfigRepository, browser: OAuthBrowser, agentId: AgentId, name: String, scope: ConfigScope?,
    changed: (ConnectState) -> Unit, paste: suspend () -> String,
) {
    val login = config.loginMcp(McpLoginParams(agentId, name, scope, browser.redirect))
    changed(ConnectState.SigningIn(login.url))
    val returned = browser.signIn(login.url) ?: run { changed(ConnectState.AwaitingPaste(login.url)); paste() }
    config.completeMcpLogin(agentId, pastedCallback(returned))
}
