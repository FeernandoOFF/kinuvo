package kinuvo.diagnostics

private const val CAUSE = "Caused by:"

/**
 * Shortens a stack trace per throwable, so a deep stack never pushes the `Caused by:` chain (the actual failure) out:
 * keeps the first [linesPerCause] lines of the exception and of each cause, and at most [maxCauses] causes.
 */
internal fun condenseTrace(trace: String, linesPerCause: Int, maxCauses: Int): String {
    val sections = mutableListOf<MutableList<String>>()
    for (line in trace.lineSequence()) {
        if (sections.isEmpty() || line.startsWith(CAUSE)) sections.add(mutableListOf())
        sections.last().add(line)
    }
    return buildList {
        sections.take(maxCauses + 1).forEach { section ->
            addAll(section.take(linesPerCause))
            if (section.size > linesPerCause) add("\t... ${section.size - linesPerCause} more lines")
        }
        if (sections.size > maxCauses + 1) add("$CAUSE ... ${sections.size - maxCauses - 1} more causes")
    }.joinToString("\n")
}
