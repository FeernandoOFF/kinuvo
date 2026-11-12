package kinuvo.server

import kinuvo.protocol.agent.AgentId
import kinuvo.protocol.session.SessionId
import kinuvo.server.plugin.SecretProvider
import kinuvo.server.plugin.SecretResolver
import kinuvo.server.plugin.Tool
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kinuvo.server.plugin.ToolRun

/**
 * Expands `${VAR}`s and Secret references in config. Resolved secrets stay in memory for [ttl] (dropped at the next use
 * after that) and are never written or sent to clients; errors name the reference, not the value. Values typed for
 * `request_secret` live in [session] and resolve only through [scoped].
 */
class Secrets(val providers: List<SecretProvider> = emptyList(), private val ttl: Duration = 5.minutes, private val time: TimeSource = TimeSource.Monotonic) : SecretResolver {
    val session = SessionSecrets(time)

    override fun scoped(agentId: AgentId, sessionId: SessionId): SecretResolver = object : SecretResolver {
        override suspend fun resolve(uri: String, reason: String) =
            if (uri.substringBefore("://") == SessionSecrets.SCHEME) session.resolve(uri, agentId, sessionId) else this@Secrets.resolve(uri, reason)
        override suspend fun redact(text: String) = this@Secrets.redact(text)
    }

    private val lock = Mutex()
    private val cache = mutableMapOf<String, Pair<String, TimeMark>>()
    /** Bumped by [refresh], so a read that started before it isn't cached after it. */
    private var generation = 0

    override suspend fun resolve(uri: String, reason: String): String {
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
        override suspend fun execute(args: JsonObject, callId: String, run: ToolRun) = redacted { tool.execute(args, callId, reporting(run)) }
        override suspend fun executeResult(args: JsonObject, callId: String, run: ToolRun) = failing { tool.executeResult(args, callId, reporting(run)) }.let {
            it.copy(output = redact(it.output), appResult = it.appResult?.let { value -> redact(value) as JsonObject })
        }
        private fun reporting(run: ToolRun) = object : ToolRun by run {
            override suspend fun progress(content: String) = run.progress(redact(content))
            override fun background(started: String, work: suspend () -> String) = run.background(started) { redacted(work) }
        }
        private suspend fun redacted(block: suspend () -> String) = redact(failing(block))
        private suspend fun <T> failing(block: suspend () -> T) = try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw IllegalStateException(redact(e.message ?: e.toString()))
        }
    }

    /** [value] with the secrets masked in its keys and strings, which JSON escaping would hide from [redact]. */
    private suspend fun redact(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.entries.associate { (k, v) -> redact(k) to redact(v) })
        is JsonArray -> JsonArray(value.map { redact(it) })
        is JsonPrimitive -> if (value.isString) JsonPrimitive(redact(value.content)) else value
    }

    /** [text] with the secrets still in memory masked, e.g. in a tool's output. */
    override suspend fun redact(text: String): String = (lock.withLock { expire(); cache.values.map { it.first } } + session.values())
        .filter { it.length >= MIN_REDACTED }.distinct().sortedByDescending { it.length }
        .fold(text) { t, secret -> t.replace(secret, MASK) }

    private fun expire() = cache.values.removeAll { it.second.elapsedNow() >= ttl }

    fun provider(scheme: String) =
        providers.firstOrNull { it.scheme == scheme } ?: error("No secret provider for $scheme:// references")

    companion object {
        private const val MASK = "••••"
        private const val MIN_REDACTED = 4
    }
}
