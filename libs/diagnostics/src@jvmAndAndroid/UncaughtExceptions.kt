package kinuvo.diagnostics

/**
 * Logs every uncaught exception and, when [fatal] says the process dies of it, writes the crash file before the previous
 * handler (which ends the process) runs. A desktop JVM survives a failing worker thread, so that isn't a crash.
 */
internal fun recordUncaughtExceptions(writer: LogWriter, crashes: CrashReports, fatal: (Thread) -> Boolean) {
    val previous = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, error ->
        if (fatal(thread)) crashes.record(thread.name, error)
        runCatching { writer.write("ERROR", "kinuvo.crash", "Uncaught ${error::class.simpleName} on ${thread.name}", error.stackTraceToString()) }
        previous?.uncaughtException(thread, error) ?: error.printStackTrace()
    }
}
