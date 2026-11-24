package kinuvo.diagnostics

/** Records the crash before the previous handler (which ends the process) runs. */
internal fun recordUncaughtExceptions(writer: LogWriter, crashes: CrashReports) {
    val previous = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, error ->
        crashes.record(thread.name, error)
        runCatching { writer.write("ERROR", "kinuvo.crash", "Uncaught ${error::class.simpleName} on ${thread.name}", error.stackTraceToString()) }
        previous?.uncaughtException(thread, error) ?: error.printStackTrace()
    }
}
