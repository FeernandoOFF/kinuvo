package kinuvo.diagnostics

/**
 * Removes credentials, e-mail addresses, URL query strings and user names from log text. Best effort: Kinuvo doesn't log
 * message text or file contents, and exception messages are shortened, but anything shared should still be reviewed.
 */
object Redaction {
    private const val MASK = "<redacted>"

    private val privateKey = Regex("-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?(?:-----END [A-Z ]*PRIVATE KEY-----|$)")
    private val header = Regex("(?im)\\b((?:proxy-)?authorization|cookie|set-cookie)(\\s*:\\s*)[^\\r\\n]+")
    private val scheme = Regex("(?i)\\b(bearer|basic)\\s+[A-Za-z0-9+/_.=~-]{6,}")
    private val named = Regex(
        "(?i)([\"']?\\b[a-z0-9_.-]*(?:api[_-]?key|apikey|secret|token|password|passwd|pwd|credentials?|pairing[_-]?code|private[_-]?key|code[_-]?verifier|device[_-]?code|user[_-]?code|session[_-]?id)\\b[\"']?\\s*[:=]\\s*)(\"[^\"]*\"|'[^']*'|[^\\s,;&}\\])]+)",
    )
    private val userInfo = Regex("(?i)\\b([a-z][a-z0-9+.-]*://)[^\\s/@?#]+@")
    private val query = Regex("(?i)\\b([a-z][a-z0-9+.-]*://[^\\s?#\"'<>]*)\\?[^\\s#\"'<>]*")
    private val email = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}")
    private val known = Regex(
        "\\b(?:sk-[A-Za-z0-9_-]{16,}|gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|xox[abprs]-[A-Za-z0-9-]{10,}|" +
            "AIza[0-9A-Za-z_-]{30,}|AKIA[0-9A-Z]{16}|ya29\\.[0-9A-Za-z_-]{20,}|eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]*)",
    )
    private val opaque = Regex("(?<![A-Za-z0-9_-])(?=[A-Za-z0-9_-]*\\d)(?=[A-Za-z0-9_-]*[A-Za-z])[A-Za-z0-9_-]{40,}(?![A-Za-z0-9_-])")
    private val unixHome = Regex("(?i)(/(?:Users|home)/)[^/\\s\"'<>:]+")
    private val windowsHome = Regex("(?i)([A-Z]:(?:\\\\{1,2}|/)Users(?:\\\\{1,2}|/))[^\\\\/\\s\"'<>:]+")

    fun apply(text: String): String {
        var value = text
        value = privateKey.replace(value, "<redacted-private-key>")
        value = header.replace(value) { "${it.groupValues[1]}${it.groupValues[2]}$MASK" }
        value = scheme.replace(value) { "${it.groupValues[1]} $MASK" }
        value = query.replace(value) { "${it.groupValues[1]}?$MASK" }
        value = userInfo.replace(value) { "${it.groupValues[1]}$MASK@" }
        value = named.replace(value) { "${it.groupValues[1]}$MASK" }
        value = email.replace(value, "<email>")
        value = known.replace(value, MASK)
        value = opaque.replace(value, MASK)
        value = unixHome.replace(value) { "${it.groupValues[1]}<user>" }
        value = windowsHome.replace(value) { "${it.groupValues[1]}<user>" }
        return value
    }
}
