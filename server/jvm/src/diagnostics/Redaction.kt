package kinuvo.server.jvm.diagnostics

/** Best effort for operational logs; users must still review exports before sharing. */
class Redaction(private val userHome: String = System.getProperty("user.home")) {
    fun apply(text: String): String {
        var value = text
        if (userHome.isNotEmpty()) {
            setOf(userHome, userHome.replace('\\', '/'), userHome.replace("\\", "\\\\")).forEach { path ->
                value = value.replace(path, "<user-home>", ignoreCase = ':' in userHome)
            }
        }
        value = pem.replace(value, "<redacted-private-key>")
        value = headers.replace(value) { "${it.groupValues[1]}<redacted>" }
        value = authorization.replace(value) { "${it.groupValues[1]}<redacted>" }
        value = credentials.replace(value) { "${it.groupValues[1]}<redacted>" }
        value = urlCredentials.replace(value, "$1<redacted>@")
        value = query.replace(value) { "${it.groupValues[1]}<redacted>" }
        value = knownToken.replace(value, "<redacted>")
        return value
    }

    private companion object {
        val headers = Regex("(?im)((?:authorization|cookie|set-cookie)\\s*:\\s*)[^\\r\\n]+")
        val pem = Regex("-----BEGIN [^-]*PRIVATE KEY-----[\\s\\S]*?-----END [^-]*PRIVATE KEY-----")
        val credentials = Regex("(?i)((?:[\"']?(?:api[_-]?key|access[_-]?token|refresh[_-]?token|token|password|passwd|secret|pairing[_-]?code|authorization|cookie|set-cookie)[\"']?)\\s*[:=]\\s*)(?:\"[^\"]*\"|'[^']*'|[^\\s,;}]+)")
        val authorization = Regex("(?i)(\\b(?:Bearer|Basic)\\s+)[a-z0-9+/_.=:-]+")
        val urlCredentials = Regex("(https?://)[^\\s/@]+(?::[^\\s/@]*)?@")
        val query = Regex("(?i)([?&#](?:code|key|token|api_key|access_token|refresh_token|password|secret|pair)=[^\\S\\r\\n]*)[^&#\\s]+")
        val knownToken = Regex("\\b(?:sk-[a-zA-Z0-9_-]{12,}|gh[pousr]_[a-zA-Z0-9_]{12,}|github_pat_[a-zA-Z0-9_]{12,}|eyJ[a-zA-Z0-9_-]+\\.[a-zA-Z0-9_-]+\\.[a-zA-Z0-9_-]+)\\b")
    }
}
