package kinuvo.client

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import kinuvo.protocol.ConnectParams

internal expect suspend fun HttpClient.prepareConnect(url: String, params: ConnectParams): ConnectParams

internal expect fun httpClient(block: HttpClientConfig<*>.() -> Unit): HttpClient
