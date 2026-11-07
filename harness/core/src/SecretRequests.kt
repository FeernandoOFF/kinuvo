package kinuvo.harness.core

import kinuvo.protocol.REFERENCE
import kinuvo.protocol.SESSION_SECRET_SCHEME
import kinuvo.protocol.SecretAnswer
import kinuvo.protocol.isReference
import kotlinx.coroutines.CompletableDeferred
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.uuid.Uuid

/**
 * `request_secret`s waiting for the user. The answer becomes a Secret reference for the Agent and Session that asked: a
 * password manager item the user picked, or a value they typed, kept in [Secrets.session]. The Agent never gets the value.
 */
@OptIn(ExperimentalAtomicApi::class)
class SecretRequests(private val secrets: Secrets) {
    private class Waiting(val scope: SecretScope, val answer: CompletableDeferred<String?>)

    private val waiting = AtomicReference(emptyMap<String, Waiting>())

    fun register(scope: SecretScope): Pending {
        val id = Uuid.random().toString()
        val deferred = CompletableDeferred<String?>()
        waiting.update { it + (id to Waiting(scope, deferred)) }
        return Pending(id, deferred)
    }

    inner class Pending internal constructor(val id: String, private val deferred: CompletableDeferred<String?>) {
        /** The `${…}` reference the user shared, or null when they declined. */
        suspend fun await(): String? = deferred.await()
        fun close() = waiting.update { it - id }
    }

    /** Returns false when nothing is waiting under the request or it was already answered; fails when the answer isn't acceptable. */
    suspend fun respond(answer: SecretAnswer): Boolean {
        val request = waiting.load()[answer.requestId] ?: return false
        val picked = answer.reference
        val typed = answer.value
        require(picked == null || typed == null) { "Pick an item or type a value, not both" }
        val reference = when {
            picked != null -> checked(picked)
            typed != null -> {
                require(typed.isNotEmpty()) { "The value is empty" }
                "\${${secrets.session.store(request.scope, typed)}}"
            }
            else -> null
        }
        return request.answer.complete(reference)
    }

    private suspend fun checked(reference: String): String {
        require(isReference(reference)) { "Not a Secret reference" }
        val uri = REFERENCE.matchEntire(reference.trim())!!.groupValues[3]
        require(uri.isNotEmpty()) { "Not a Secret reference" }
        val scheme = uri.substringBefore("://")
        require(scheme != SESSION_SECRET_SCHEME) { "Pick an item from a password manager" }
        val provider = secrets.provider(scheme)
        require(provider.status().connected) { "${provider.name} isn't connected" }
        return reference.trim()
    }
}
