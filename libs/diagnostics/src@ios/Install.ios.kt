package kinuvo.diagnostics

import io.github.oshai.kotlinlogging.KotlinLoggingConfiguration
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.setUnhandledExceptionHook

@OptIn(ExperimentalNativeApi::class)
internal actual fun installPlatform(writer: LogWriter, crashes: CrashReports) {
    val config = KotlinLoggingConfiguration.direct
    config.appender = writer.appender(config.appender)
    // The runtime ends the process once the hook returns.
    setUnhandledExceptionHook { error ->
        crashes.record("main", error)
        runCatching { writer.write("ERROR", "kinuvo.crash", "Unhandled ${error::class.simpleName}", error.stackTraceToString()) }
        error.printStackTrace()
    }
}
