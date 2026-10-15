package kinuvo.client

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import kinuvo.protocol.ConnectParams
import kinuvo.protocol.ProtocolJson
import kotlinx.browser.window

internal actual suspend fun HttpClient.prepareConnect(url: String, params: ConnectParams): ConnectParams {
    val base = url.substringBefore("://").let { if (it == "wss") "https" else "http" } + "://" + url.substringAfter("://").substringBefore('/')
    fun origin(value: String) = Url(value).let { Triple(it.protocol.name, it.host.lowercase(), it.port) }
    require(origin(base) == origin(window.location.href)) { "Open the web app at the Server's own HTTPS address" }
    val request = params.copy(token = params.token?.takeUnless { it == "browser-session" })
    val response = post("$base/auth/session") {
        header(HttpHeaders.ContentType, "application/json")
        setBody(ProtocolJson.encodeToString(ConnectParams.serializer(), request))
    }
    if (response.status.value == 401) throw AuthException("Invalid or expired browser session; pair this Device again")
    check(response.status.value == 204) { "Browser authentication failed (${response.status.value})" }
    return params.copy(token = null, pairingCode = null)
}

internal actual fun httpClient(block: HttpClientConfig<*>.() -> Unit) = HttpClient(block)
