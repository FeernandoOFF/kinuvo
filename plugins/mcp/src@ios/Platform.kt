package kinuvo.plugins.mcp

import io.modelcontextprotocol.kotlin.sdk.shared.Transport
import kinuvo.server.plugin.Keychain
import kinuvo.server.plugin.PluginStorage
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlinx.io.files.Path
import platform.Security.SecRandomCopyBytes
import platform.Security.kSecRandomDefault

@OptIn(ExperimentalForeignApi::class)
internal actual fun secureRandomBytes(count: Int): ByteArray = ByteArray(count).also { bytes ->
    bytes.usePinned { check(SecRandomCopyBytes(kSecRandomDefault, count.convert(), it.addressOf(0)) == 0) { "No secure random bytes" } }
}

/** In the app's sandbox, which only it can read. */
internal actual fun defaultSignIns(files: PluginStorage, keychain: Keychain?): McpSignInStore = SandboxSignIns(Path(files.home, "mcp-auth"))

internal actual val stdioSupported: Boolean = false

internal actual fun startStdio(config: McpServerConfig.Stdio): StdioProcess =
    throw UnsupportedOperationException("${config.name} is a local (stdio) MCP server, which needs a desktop Server")

/** iOS ends the app without a hook; there are no local server processes to stop anyway. */
internal actual fun onShutdown(block: () -> Unit): () -> Unit = {}
