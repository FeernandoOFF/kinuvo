package kinuvo.gateways.mcp

import arrow.core.raise.Raise
import arrow.core.raise.context.raise
import kinuvo.harness.*
import kinuvo.protocol.WireErrorCode
import kinuvo.server.ServerError
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

sealed class McpError(override val code: String, override val message: String) : ServerError {
    data object InvalidCredential : McpError(WireErrorCode.Forbidden, "Invalid or expired run credential")
    data object UnknownTool : McpError(WireErrorCode.NotFound, "Tool is outside this run's Tool scope")
}

/** Only digests are indexed; a credential is returned once to the owning harness. */
class McpAccess {
    class Run(val tools: ToolProvider, val gate: ToolGate, val turn: ToolTurn, val job: Job, val maxResultTokens: Int = Int.MAX_VALUE) {
        val calls = Mutex()
    }
    private val runs = mutableMapOf<String, Run>()
    private val random = SecureRandom()
    private fun digest(token: String) = MessageDigest.getInstance("SHA-256").digest(token.toByteArray())

    @Synchronized fun issue(run: Run): String {
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))
        runs[Base64.getEncoder().encodeToString(digest(token))] = run
        return token
    }

    context(_: Raise<McpError>)
    @Synchronized fun resolve(authorization: String?): Run {
        val token = authorization?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ") ?: raise(McpError.InvalidCredential)
        val hash = digest(token)
        return runs.entries.firstOrNull { MessageDigest.isEqual(Base64.getDecoder().decode(it.key), hash) }?.value
            ?.takeIf { it.job.isActive } ?: raise(McpError.InvalidCredential)
    }

    @Synchronized fun revoke(run: Run) {
        runs.entries.removeAll { it.value === run }
        run.job.cancel()
    }
}
