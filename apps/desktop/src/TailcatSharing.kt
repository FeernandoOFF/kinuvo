package kinuvo.desktop

import kinuvo.app.connection.ServerSharing
import kinuvo.app.connection.SharingState
import kotlinx.coroutines.CancellationException

internal class TailcatSharing(
    private val saved: () -> Boolean,
    private val save: (Boolean) -> Unit,
    private val active: () -> Boolean,
    private val toggle: suspend (Boolean) -> Unit,
    private val resetAddress: suspend () -> Unit,
) : ServerSharing {
    private var error: String? = null
    override val enabled get() = active()
    override val state get() = error?.let(SharingState::Error) ?: if (enabled) SharingState.On else SharingState.Off

    override suspend fun share(enabled: Boolean) {
        toggle(enabled)
        save(enabled)
        error = null
    }

    override suspend fun reset() { resetAddress(); error = null }

    suspend fun restore() {
        if (!saved()) return
        try { share(true) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { error = "Could not start Tailcat; check the native library and try again" }
    }
}
