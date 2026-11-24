package kinuvo.diagnostics

import io.github.oshai.kotlinlogging.Appender
import io.github.oshai.kotlinlogging.KLoggingEvent
import kotlin.time.Clock

/** Formats, redacts and shortens log events into a [RotatingLog]. */
class LogWriter(private val log: RotatingLog, private val now: () -> String = { Clock.System.now().toString() }) {
    fun write(level: String, logger: String?, message: String?, trace: String? = null) {
        val head = "${now()} ${level.padEnd(5)} ${logger.orEmpty()} - ${message.orEmpty()}"
        log.append(Redaction.apply(head.take(MAX_LINE)))
        trace?.lineSequence()?.take(MAX_TRACE_LINES)?.forEach { log.append(Redaction.apply(it.take(MAX_LINE))) }
    }

    /** A kotlin-logging appender that writes here and then to [next], e.g. the platform's console. */
    fun appender(next: Appender?): Appender = object : Appender {
        override fun log(loggingEvent: KLoggingEvent) {
            next?.log(loggingEvent)
            runCatching { write(loggingEvent.level.name, loggingEvent.loggerName, loggingEvent.message, loggingEvent.cause?.stackTraceToString()) }
        }
    }

    private companion object {
        const val MAX_LINE = 1000
        const val MAX_TRACE_LINES = 40
    }
}
