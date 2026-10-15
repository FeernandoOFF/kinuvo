package kinuvo.harness.mcp

import kinuvo.harness.core.store.AgentFiles
import kotlinx.io.files.Path
import java.security.SecureRandom

private val random = SecureRandom()

internal actual fun secureRandomBytes(count: Int) = ByteArray(count).also(random::nextBytes)

internal actual fun defaultSignIns(files: AgentFiles): McpSignInStore = SandboxSignIns(Path(files.home, "mcp-auth"))

internal actual val stdioSupported: Boolean = false

internal actual fun startStdio(config: McpServerConfig.Stdio): StdioProcess =
    throw UnsupportedOperationException("${config.name} is a local (stdio) MCP server, which needs a desktop Server")

/** Android ends the app without a hook; there are no local server processes to stop anyway. */
internal actual fun onShutdown(block: () -> Unit): () -> Unit = {}
