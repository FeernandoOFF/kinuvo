package kinuvo.app.connection

import kinuvo.app.agent.ConfigRepository
import kinuvo.client.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal class ProviderSignIn(
    private val config: ConfigRepository,
    private val browser: OAuthBrowser?,
    private val scope: CoroutineScope,
    private val changed: (ProviderSignInState?) -> Unit,
    private val completed: (String) -> Unit = {},
) {
    private var job: Job? = null
    private var current: ProviderSignInState? = null
    private fun update(value: ProviderSignInState?) { current = value; changed(value) }

    fun start(provider: String, anotherAccount: Boolean = false, requestConsent: Boolean = false) {
        cancel()
        val cancelled = job
        update(ProviderSignInState(provider))
        job = scope.launch {
            try {
                cancelled?.join()
                config.cancelProviderSignIn(provider)
                val callback = if (browser != null) browser.signInWithLoopback(
                    authorize = { config.signInProvider(provider, it, anotherAccount, requestConsent) },
                    opened = { url, paste -> update(ProviderSignInState(provider, url, paste)) },
                ) else {
                    update(ProviderSignInState(provider, config.signInProvider(provider, "http://127.0.0.1:1455/auth/callback", anotherAccount, requestConsent), true))
                    null
                }
                if (callback != null) finish(provider, callback)
                else current?.let { update(it.copy(awaitingPaste = true)) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { current?.let { update(it.copy(awaitingPaste = it.url != null, error = e.userMessage)) } }
        }
    }

    fun complete(callback: String) {
        val provider = current?.provider ?: return
        job?.cancel()
        job = scope.launch {
            try { finish(provider, pastedCallback(callback)) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { current?.let { update(it.copy(awaitingPaste = true, error = e.userMessage)) } }
        }
    }
    private suspend fun finish(provider: String, callback: String) {
        config.completeProviderSignIn(provider, callback)
        update(null)
        completed(provider)
    }
    fun cancel() {
        val previous = job
        val provider = current?.provider
        previous?.cancel()
        job = scope.launch {
            previous?.join()
            if (provider != null) try { config.cancelProviderSignIn(provider) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { }
        }
        update(null)
    }
}

/** Browsers copy a loopback address without its scheme, e.g. Safari's `127.0.0.1:1455/auth/callback?…`. */
internal fun pastedCallback(text: String): String = text.trim().let { if ("://" in it) it else "http://$it" }
