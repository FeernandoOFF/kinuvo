package kinuvo.harness.server

import co.touchlab.stately.collections.ConcurrentMutableMap
import kinuvo.harness.core.ModelProvider
import kinuvo.protocol.DeviceCode
import kinuvo.protocol.ProviderInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Model provider sign-ins: each runs in the background after handing its device code to the client; [changed] follows. */
class ProviderLogins(private val providers: List<ModelProvider>, private val scope: CoroutineScope, private val changed: suspend (List<ProviderInfo>) -> Unit) {
    private val running = ConcurrentMutableMap<String, Job>()
    private val errors = ConcurrentMutableMap<String, String>()
    private val lock = Mutex()

    suspend fun list() = providers.map { ProviderInfo(it.id, it.name, it.auth?.account(), errors[it.id], it.apiKey?.status()) }

    suspend fun login(id: String): DeviceCode {
        val auth = provider(id).auth ?: error("${provider(id).name} needs no sign-in")
        val code = CompletableDeferred<DeviceCode>()
        lock.withLock {
            running.remove(id)?.cancelAndJoin()
            errors.remove(id)
            val job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    auth.login { code.complete(it) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    errors[id] = e.message ?: "Sign-in failed"
                    code.completeExceptionally(e)
                }
                changed(list())
            }
            job.invokeOnCompletion { running.removeExact(id, job); code.completeExceptionally(it ?: IllegalStateException("Sign-in ended")) }
            running[id] = job
            job.start()
        }
        return code.await()
    }

    /** Stops a sign-in in progress first, so it can't save credentials after signing out. */
    suspend fun logout(id: String) {
        lock.withLock {
            running.remove(id)?.cancelAndJoin()
            provider(id).auth?.logout()
        }
        changed(list())
    }

    suspend fun saveApiKey(id: String, value: String) {
        val key = provider(id).apiKey ?: error("${provider(id).name} doesn't use an API key")
        key.save(value)
        changed(list())
    }

    private fun provider(id: String) = providers.firstOrNull { it.id == id } ?: error("No model provider '$id'")
}
