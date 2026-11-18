package kinuvo.tailcat

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

internal object TailcatJni {
    external fun abiVersion(): Int
    external fun call(method: String, params: String): String
}

internal actual val blockingDispatcher: CoroutineDispatcher = Dispatchers.IO

internal actual fun nativeCall(): (String, String) -> String = try {
    System.loadLibrary("KinuvoTailcat")
    check(TailcatJni.abiVersion() == 3) { "Unsupported Tailcat ABI" }
    TailcatJni::call
} catch (e: LinkageError) { throw IllegalStateException("Tailcat native library could not be loaded", e) }

internal actual fun onShutdown(action: () -> Unit) { Runtime.getRuntime().addShutdownHook(Thread(action)) }
