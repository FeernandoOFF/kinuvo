package kinuvo.harness.mcp

import io.modelcontextprotocol.kotlin.sdk.shared.Transport
import kinuvo.harness.core.store.AgentFiles
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlinx.io.files.Path
import platform.CoreCrypto.CC_SHA256
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH
import platform.Security.SecRandomCopyBytes
import platform.Security.kSecRandomDefault

@OptIn(ExperimentalForeignApi::class)
internal actual fun sha256(bytes: ByteArray): ByteArray {
    val digest = UByteArray(CC_SHA256_DIGEST_LENGTH)
    val input = if (bytes.isEmpty()) byteArrayOf(0) else bytes
    input.usePinned { i -> digest.usePinned { d -> CC_SHA256(i.addressOf(0), bytes.size.convert(), d.addressOf(0)) } }
    return digest.asByteArray()
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun secureRandomBytes(count: Int): ByteArray = ByteArray(count).also { bytes ->
    bytes.usePinned { check(SecRandomCopyBytes(kSecRandomDefault, count.convert(), it.addressOf(0)) == 0) { "No secure random bytes" } }
}

/** In the app's sandbox, which only it can read. */
internal actual fun defaultSignIns(files: AgentFiles): McpSignInStore = SandboxSignIns(Path(files.home, "mcp-auth"))

internal actual fun startStdio(config: McpServerConfig.Stdio): StdioProcess =
    throw UnsupportedOperationException("${config.name} is a local (stdio) MCP server, which needs a desktop Server")

/** iOS ends the app without a hook; there are no local server processes to stop anyway. */
internal actual fun onShutdown(block: () -> Unit): () -> Unit = {}
