package kinuvo.protocol

/** Cleartext is allowed only for explicitly local or private-network addresses. */
fun privateNetworkHost(value: String): Boolean {
    val host = value.removeSurrounding("[", "]").lowercase()
    if (host == "localhost" || host.endsWith(".local")) return true
    if (host.none { it == '.' || it == ':' }) return host.any { it in 'a'..'z' } &&
        host.all { it in 'a'..'z' || it in '0'..'9' || it in "-_" } && !Regex("0x[0-9a-f]+").matches(host)
    if (host == "::1" || host.startsWith("fc") && ':' in host || host.startsWith("fd") && ':' in host || host.startsWith("fe80:") ) return true
    val parts = host.split('.').map {
        if (it.length > 1 && it.startsWith('0') || it.any { char -> char !in '0'..'9' }) return false
        it.toIntOrNull() ?: return false
    }
    if (parts.size != 4 || parts.any { it !in 0..255 }) return false
    return parts[0] == 127 || parts[0] == 10 || parts[0] == 192 && parts[1] == 168 ||
        parts[0] == 172 && parts[1] in 16..31 || parts[0] == 100 && parts[1] in 64..127 || parts[0] == 169 && parts[1] == 254
}

/** Checks [url] is a Server address; cleartext ones must be local or private unless [allowCleartext]. */
fun requireSecureTransport(url: String, allowCleartext: Boolean = false) {
    val scheme = url.substringBefore("://")
    require(scheme in setOf("ws", "wss")) { "Use a WebSocket Server URL" }
    val authority = url.substringAfter("://").substringBefore('/')
    require(authority.isNotBlank() && authority.none { it in "@?#\\" || it.isWhitespace() }) { "Invalid Server address" }
    val host = if (authority.startsWith('[')) authority.substringBefore(']') + "]" else authority.substringBefore(':')
    require(scheme == "wss" || allowCleartext || privateNetworkHost(host)) { "Public Servers require HTTPS/WSS" }
}
