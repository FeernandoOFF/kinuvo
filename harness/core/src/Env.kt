package kinuvo.harness.core

private val LINE = Regex("""^(?:export\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$""")

/** `${VAR}`, `${VAR:-default}` or a Secret reference `${scheme://…}`: groups name, default, uri. */
internal val REFERENCE = Regex("""\$\{(?:([A-Za-z_][A-Za-z0-9_]*)(?::-([^}]*))?|([a-z][a-z0-9+.-]*://[^}]+))\}""")

/** `KEY=value` lines; `#` comments, optional `export `, quoted values. */
fun parseDotEnv(text: String): Map<String, String> = text.lines().mapNotNull { raw ->
    val m = LINE.find(raw.trim()) ?: return@mapNotNull null
    m.groupValues[1] to unquote(m.groupValues[2].trim())
}.toMap()

private fun unquote(v: String): String = when {
    v.length >= 2 && v.first() == '"' && v.last() == '"' -> v.substring(1, v.length - 1).replace("\\n", "\n").replace("\\\"", "\"")
    v.length >= 2 && v.first() == '\'' && v.last() == '\'' -> v.substring(1, v.length - 1)
    else -> v.substringBefore(" #")
}
