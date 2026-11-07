package kinuvo.harness.core

import kinuvo.harness.core.tools.Tool
import kinuvo.protocol.REFERENCE
import kinuvo.protocol.SESSION_SECRET_SCHEME
import kinuvo.protocol.SecretEntry
import kinuvo.protocol.SecretProviderStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kinuvo.harness.core.tools.ToolRun
import kotlin.uuid.Uuid

/** A password manager that config can point into with Secret references, e.g. Proton Pass for `${pass://Vault/Item/field}`. */
interface SecretProvider {
    /** The URI scheme of its references, e.g. `pass`. */
    val scheme: String
    val name: String
    /** The value at [uri]; [reason] says what needs it, for the provider's audit log. */
    suspend fun resolve(uri: String, reason: String): String
    suspend fun status(): SecretProviderStatus
    /** Keeps the [token] it signs in with. */
    suspend fun connect(token: String)
    suspend fun disconnect()
    /** Names under [path], without values: vaults at the root, then items, then fields. */
    suspend fun browse(path: List<String>): List<SecretEntry>
}

/** Whose `secret://` references a call may use: the Agent and Session the user typed the value for. */
data class SecretScope(val agentId: String, val sessionId: String)

/**
 * Values the user typed for a `request_secret`, kept in memory only: each is usable by the [SecretScope] it was typed
 * for and dropped after [ttl].
 */
class SessionSecrets(private val ttl: Duration = 24.hours, private val time: TimeSource = TimeSource.Monotonic) {
    private class Entry(val scope: SecretScope, val value: String, val stored: TimeMark)

    private val lock = Mutex()
    private val entries = mutableMapOf<String, Entry>()

    /** Keeps [value] for [scope] and returns its `secret://` URI. */
    suspend fun store(scope: SecretScope, value: String): String = lock.withLock {
        expire()
        val id = Uuid.random().toString()
        entries[id] = Entry(scope, value, time.markNow())
        "$SESSION_SECRET_SCHEME://$id"
    }

    suspend fun resolve(uri: String, scope: SecretScope?): String = lock.withLock {
        expire()
        entries[uri.substringAfter("://")]?.takeIf { it.scope == scope }?.value
            ?: error("Can't read $uri: only the Session it was shared in can use it, and only for 24 hours")
    }

    internal suspend fun values(): List<String> = lock.withLock { expire(); entries.values.map { it.value } }

    private fun expire() = entries.values.removeAll { it.stored.elapsedNow() >= ttl }
}

/**
 * Expands `${VAR}`s and Secret references in config. Resolved secrets stay in memory for [ttl] (dropped at the next use
 * after that) and are never written or sent to clients; errors name the reference, not the value.
 */
class Secrets(val providers: List<SecretProvider> = emptyList(), private val ttl: Duration = 5.minutes, private val time: TimeSource = TimeSource.Monotonic) {
    private val lock = Mutex()
    private val cache = mutableMapOf<String, Pair<String, TimeMark>>()
    /** Typed values, which only the [SecretScope] they were typed for resolves; none count as a password manager. */
    val session = SessionSecrets(time = time)
    /** Bumped by [refresh], so a read that started before it isn't cached after it. */
    private var generation = 0

    /**
     * [value] with `${VAR}`s from [env] and Secret references resolved for [reason], e.g. "Kinuvo: MCP server github".
     * A variable's own value can hold Secret references too, e.g. `GITHUB_TOKEN=${pass://Kinuvo/GitHub/token}` in `.env`.
     * A `secret://` reference resolves only for the [scope] it was shared in.
     */
    suspend fun expand(value: String, env: Map<String, String>, reason: String, scope: SecretScope? = null): String = replace(value) { name, uri, m ->
        if (uri.isNotEmpty()) resolve(uri, reason, scope)
        else env[name]?.let { v -> replace(v) { _, inner, whole -> if (inner.isNotEmpty()) resolve(inner, reason) else whole.value } }
            ?: m.groups[2]?.value ?: error("environment variable $name is not set")
    }

    private suspend fun replace(value: String, by: suspend (name: String, uri: String, MatchResult) -> String): String {
        val out = StringBuilder()
        var last = 0
        for (m in REFERENCE.findAll(value)) {
            out.append(value, last, m.range.first).append(by(m.groupValues[1], m.groupValues[3], m))
            last = m.range.last + 1
        }
        return out.append(value, last, value.length).toString()
    }

    suspend fun resolve(uri: String, reason: String, scope: SecretScope? = null): String {
        if (uri.substringBefore("://") == SESSION_SECRET_SCHEME) return session.resolve(uri, scope)
        val started = lock.withLock {
            expire()
            cache[uri]?.let { return it.first }
            generation
        }
        val value = try {
            provider(uri.substringBefore("://")).resolve(uri, reason)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw IllegalStateException("Can't read $uri: ${e.message ?: e::class.simpleName}")
        }
        lock.withLock { if (generation == started) cache[uri] = value to time.markNow() }
        return value
    }

    /** Forgets resolved values, so the next use reads them again. */
    suspend fun refresh() = lock.withLock {
        cache.clear()
        generation++
    }

    /** [tool] with the secrets still in memory masked in what it returns or fails with. */
    fun redacting(tool: Tool): Tool = object : Tool by tool {
        override suspend fun execute(args: JsonObject) = redacted { tool.execute(args) }
        override suspend fun execute(args: JsonObject, callId: String) = redacted { tool.execute(args, callId) }
        override suspend fun execute(args: JsonObject, callId: String, run: ToolRun) = redacted { tool.execute(args, callId, run) }
        private suspend fun redacted(block: suspend () -> String) = try {
            redact(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw IllegalStateException(redact(e.message ?: e.toString()))
        }
    }

    /** [text] with the secrets still in memory masked, e.g. in a tool's output. */
    suspend fun redact(text: String): String = (lock.withLock { expire(); cache.values.map { it.first } } + session.values())
        .filter { it.length >= MIN_REDACTED }.sortedByDescending { it.length }
        .fold(text) { t, secret -> t.replace(secret, MASK) }

    private fun expire() = cache.values.removeAll { it.second.elapsedNow() >= ttl }

    fun provider(scheme: String) =
        providers.firstOrNull { it.scheme == scheme } ?: error("No secret provider for $scheme:// references")

    companion object {
        private const val MASK = "••••"
        private const val MIN_REDACTED = 4

        /** Whether [value] is a single `${…}` reference, which isn't secret itself. */
        fun isReference(value: String) = kinuvo.protocol.isReference(value)

        fun hasReferences(value: String) = REFERENCE.containsMatchIn(value)

        /** Whether [value] has a Secret reference (not just `${VAR}`s). */
        fun hasSecret(value: String) = REFERENCE.findAll(value).any { it.groupValues[3].isNotEmpty() }
    }
}
