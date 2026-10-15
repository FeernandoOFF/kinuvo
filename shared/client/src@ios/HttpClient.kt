package kinuvo.client

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.darwin.Darwin

internal actual fun httpClient(block: HttpClientConfig<*>.() -> Unit) = HttpClient(Darwin, block)
