package kinuvo.protocol

/** `kinuvo://` links that open the app at a Session, e.g. from a notification, or pair it with a Server. */
object Links {
    private val CHAT = Regex("""kinuvo://chat/([a-z0-9][a-z0-9-]{0,31})(?:/([A-Za-z0-9_-]{1,128}))?/?""")

    fun chat(agentId: String, sessionId: String? = null) = "kinuvo://chat/$agentId" + (sessionId?.let { "/$it" } ?: "")

    /** The Agent and Session of a chat link; null when [url] isn't one. */
    fun parseChat(url: String): Pair<String, String?>? = CHAT.matchEntire(url.trim())?.let { it.groupValues[1] to it.groupValues[2].ifEmpty { null } }

    /** Opens the app ready to pair with [server]; what pairing QR codes hold. */
    fun pair(server: String, code: String) = "kinuvo://pair?server=${encode(server)}&code=${encode(code)}"

    /** Opens the web app served by [baseUrl] ready to pair; the code stays in the fragment, so it never reaches the Server. */
    fun webPair(baseUrl: String, code: String) = "${baseUrl.removeSuffix("/")}/#pair=${encode(code)}"

    /** The Server and code of an app or web pairing link; null when [text] isn't one. */
    fun parsePair(text: String): PairLink? {
        val url = text.trim()
        if (url.startsWith("kinuvo://pair?")) {
            val query = url.substringAfter('?').split('&').associate { it.substringBefore('=') to decode(it.substringAfter('=', "")) }
            val server = query["server"]?.takeIf { it.isNotBlank() } ?: return null
            return PairLink(server, query["code"]?.takeIf(::isCode) ?: return null)
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) return null
        val code = url.substringAfter('#', "").split('&').firstOrNull { it.startsWith("pair=") }?.let { decode(it.removePrefix("pair=")) } ?: return null
        if (!isCode(code)) return null
        val scheme = url.substringBefore("://")
        val host = url.substringAfter("://").substringBefore('#').substringBefore('/').substringBefore('?')
        return PairLink("$scheme://$host", code)
    }

    private fun isCode(code: String) = code.length in 4..64 && code.all { it.isLetterOrDigit() }

    private fun encode(s: String) = buildString {
        s.encodeToByteArray().forEach { b ->
            val c = b.toInt().toChar()
            if (c.isLetterOrDigit() && c.code < 128 || c in "-._~") append(c) else append('%').append((b.toInt() and 0xff).toString(16).uppercase().padStart(2, '0'))
        }
    }

    private fun decode(s: String): String {
        val out = ArrayList<Byte>()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length) {
                s.substring(i + 1, i + 3).toIntOrNull(16)?.let { out += it.toByte(); i += 3; continue }
            }
            out += (if (c == '+') " " else c.toString()).encodeToByteArray().toList()
            i++
        }
        return out.toByteArray().decodeToString()
    }
}
