package kinuvo.client

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.cio.CIO
import kinuvo.protocol.ConnectParams

internal actual suspend fun HttpClient.prepareConnect(url: String, params: ConnectParams) = params

/** CIO, even when the app also bundles another engine, e.g. OkHttp for model providers on Android. */
internal actual fun httpClient(block: HttpClientConfig<*>.() -> Unit) = HttpClient(CIO, block)
