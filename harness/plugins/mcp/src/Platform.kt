package kinuvo.harness.mcp

import io.modelcontextprotocol.kotlin.sdk.shared.Transport
import kinuvo.harness.core.store.AgentFiles

internal expect fun secureRandomBytes(count: Int): ByteArray

internal expect fun defaultSignIns(files: AgentFiles): McpSignInStore

/** A local MCP server process, spoken to over its stdin and stdout. */
internal interface StdioProcess {
    val transport: Transport
    /** Its last stderr lines and exit code, to explain a failure. */
    fun detail(): String?
    fun destroy()
    fun destroyNow()
}

/** Whether this platform can start local (stdio) MCP server processes. */
internal expect val stdioSupported: Boolean

internal expect fun startStdio(config: McpServerConfig.Stdio): StdioProcess

/** Runs [block] when the process exits; returns what cancels it. */
internal expect fun onShutdown(block: () -> Unit): () -> Unit
