package kinuvo.app.connection

import kinuvo.client.oauthState

/** The path our loopback listener expects an OAuth redirect on. */
const val LOOPBACK_CALLBACK_PATH = "/auth/callback"

/** The path of a bare `GET` request line, e.g. `"GET /auth/callback?… HTTP/1.1"`; null otherwise. */
fun loopbackRequestPath(requestLine: String): String? {
    val parts = requestLine.split(' ')
    return parts.getOrNull(1).takeIf {
        parts.size == 3 && parts[0] == "GET" && parts[2] in setOf("HTTP/1.0", "HTTP/1.1") &&
            it?.startsWith("/") == true && !it.startsWith("//") && '#' !in it
    }
}

/** Whether a request for [path] is our callback and carries the [expected] `state`, given the full [callback] URL it forms. */
fun loopbackMatchesState(path: String, callback: String, expected: String?): Boolean =
    path.substringBefore('?') == LOOPBACK_CALLBACK_PATH && !expected.isNullOrEmpty() && oauthState(callback) == expected

/** A minimal HTTP/1.1 response carrying [page] as its body. */
fun loopbackHttpResponse(ok: Boolean, page: String, contentType: String = "text/plain"): String =
    "HTTP/1.1 ${if (ok) "200 OK" else "400 Bad Request"}\r\nContent-Type: $contentType\r\nContent-Length: ${page.encodeToByteArray().size}\r\nConnection: close\r\n\r\n$page"
