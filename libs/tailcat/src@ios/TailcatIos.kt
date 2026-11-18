package kinuvo.tailcat

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO

private var installed: ((String, String) -> String)? = null

/** Hands over the Swift wrapper of the linked C library: [call] runs one `kinuvo_tailcat_call` and returns its JSON. */
fun installTailcat(call: (String, String) -> String) { installed = call }

internal actual fun nativeCall(): (String, String) -> String = checkNotNull(installed) { "Tailcat library unavailable" }

internal actual val blockingDispatcher: CoroutineDispatcher = Dispatchers.IO

internal actual fun onShutdown(action: () -> Unit) {}
