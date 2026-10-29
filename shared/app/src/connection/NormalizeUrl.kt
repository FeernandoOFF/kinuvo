package kinuvo.app.connection

import kinuvo.protocol.*

/** `host[:port]` or a full URL → `ws://host:port/ws`; plain http to a public host only with [allowCleartext]. */
fun normalizeUrl(input: String, allowCleartext: Boolean = false): String {
    val text = input.trim().removeSuffix("/")
    val bare = "://" !in text
    val bareHost = if (text.startsWith('[')) text.substringBefore(']') + "]" else text.substringBefore(':').substringBefore('/')
    val scheme = if (bare) { if (privateNetworkHost(bareHost)) "ws" else "wss" } else text.substringBefore("://").lowercase()
    require(scheme in setOf("http", "https", "ws", "wss")) { "Use a server address or an http(s)/ws(s) URL" }
    val rest = if (bare) text else text.substringAfter("://")
    val authority = rest.substringBefore('/')
    val host = if (authority.startsWith('[')) authority.substringBefore(']') + "]" else authority.substringBefore(':')
    require(host.isNotBlank() && authority.none { it.isWhitespace() || it in "@?#\\" }) { "Invalid server address" }
    val port = if (authority.startsWith('[')) {
        require(']' in authority && authority.substringAfter(']').let { it.isEmpty() || it.startsWith(':') }) { "Invalid IPv6 address" }
        authority.substringAfter(']').removePrefix(":")
    } else {
        require(authority.count { it == ':' } <= 1) { "Put IPv6 addresses in brackets" }
        authority.substringAfter(':', "")
    }
    val secure = scheme in setOf("https", "wss")
    val number = if (port.isEmpty()) { if (bare && !secure) 8787 else if (secure) 443 else 80 } else port.toIntOrNull()
    require(number != null && number in 1..65535) { "Invalid server port" }
    val path = rest.substringAfter('/', "").let { if (it.isEmpty()) "ws" else it }
    require('?' !in path && '#' !in path) { "Paste a pairing link or a server URL" }
    return "${if (secure) "wss" else "ws"}://$host:$number/$path".also { requireSecureTransport(it, allowCleartext) }
}
