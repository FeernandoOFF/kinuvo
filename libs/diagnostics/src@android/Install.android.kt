package kinuvo.diagnostics

import android.util.Log
import io.github.oshai.kotlinlogging.Appender
import io.github.oshai.kotlinlogging.DirectLoggerFactory
import io.github.oshai.kotlinlogging.KLoggingEvent
import io.github.oshai.kotlinlogging.KotlinLoggingConfiguration
import io.github.oshai.kotlinlogging.Level

/** kotlin-logging's own Android backend can't be teed, so loggers go through its direct factory and this appender writes to Logcat. */
private object Logcat : Appender {
    override fun log(loggingEvent: KLoggingEvent) {
        val priority = when (loggingEvent.level) {
            Level.TRACE -> Log.VERBOSE
            Level.DEBUG -> Log.DEBUG
            Level.INFO -> Log.INFO
            Level.WARN -> Log.WARN
            Level.ERROR -> Log.ERROR
            Level.OFF -> Log.ASSERT
        }
        Log.println(priority, loggingEvent.loggerName.orEmpty().takeLast(23), loggingEvent.message.orEmpty() + (loggingEvent.cause?.let { "\n" + Log.getStackTraceString(it) }.orEmpty()))
    }
}

internal actual fun installPlatform(writer: LogWriter, crashes: CrashReports) {
    KotlinLoggingConfiguration.loggerFactory = DirectLoggerFactory
    KotlinLoggingConfiguration.direct.appender = writer.appender(Logcat)
    recordUncaughtExceptions(writer, crashes)
}
