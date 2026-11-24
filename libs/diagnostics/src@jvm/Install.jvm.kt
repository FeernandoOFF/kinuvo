package kinuvo.diagnostics

import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.ThrowableProxyUtil
import ch.qos.logback.core.AppenderBase
import org.slf4j.Logger
import org.slf4j.LoggerFactory

private class LogbackWriter(private val writer: LogWriter) : AppenderBase<ILoggingEvent>() {
    override fun append(event: ILoggingEvent) {
        writer.write(event.level.toString(), event.loggerName, event.formattedMessage, event.throwableProxy?.let(ThrowableProxyUtil::asString))
    }
}

internal actual fun installPlatform(writer: LogWriter, crashes: CrashReports) {
    (LoggerFactory.getILoggerFactory() as? LoggerContext)?.let { context ->
        val appender = LogbackWriter(writer).also { it.context = context; it.name = "KINUVO_DIAGNOSTICS"; it.start() }
        context.getLogger(Logger.ROOT_LOGGER_NAME).addAppender(appender)
    }
    recordUncaughtExceptions(writer, crashes) { it.name == "main" }
}
