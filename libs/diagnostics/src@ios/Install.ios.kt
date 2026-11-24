package kinuvo.diagnostics

import co.touchlab.stately.concurrency.AtomicBoolean
import io.github.oshai.kotlinlogging.KotlinLoggingConfiguration
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.ReportUnhandledExceptionHook
import kotlin.native.setUnhandledExceptionHook
import kotlin.native.terminateWithUnhandledException

/**
 * The hook only runs the runtime's unhandled-exception processing, which ends the process just for exceptions that
 * reach the top of a thread. Coroutine and Compose frame failures end there too without terminating, leaving a frozen
 * app and no crash report, so after recording we chain the previous hook and terminate explicitly (iOS then writes its own report).
 */
@OptIn(ExperimentalNativeApi::class)
internal actual fun installPlatform(writer: LogWriter, crashes: CrashReports) {
    val config = KotlinLoggingConfiguration.direct
    config.appender = writer.appender(config.appender)
    val terminating = AtomicBoolean(false)
    var previous: ReportUnhandledExceptionHook? = null
    previous = setUnhandledExceptionHook { error ->
        // terminateWithUnhandledException calls this hook again; a second crash while dying has nothing to add.
        if (terminating.compareAndSet(false, true)) {
            runCatching { crashes.record("main", error) }
            runCatching { writer.write("ERROR", "kinuvo.crash", "Unhandled ${error::class.simpleName}", error.stackTraceToString()) }
            runCatching { error.printStackTrace() }
            runCatching { previous?.invoke(error) }
            terminateWithUnhandledException(error)
        }
    }
}
