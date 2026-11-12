package kinuvo.harness

/** The words of a search [query] that tell two searches apart. */
fun queryWords(query: String): Set<String> =
    query.lowercase().split(Regex("[^\\p{L}\\p{N}]+"))
        .filter { it.isNotBlank() && it !in setOf("a", "an", "the", "is", "who", "what", "of", "in", "on", "for", "to", "and") }.toSet()

/** Screenshots a Computer tool shows the model are named so, and only the latest few are sent again. */
const val SCREENSHOT_PREFIX = "screen-"
