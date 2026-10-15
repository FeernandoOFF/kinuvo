package kinuvo.client

import io.ktor.client.HttpClient
import kinuvo.protocol.ConnectParams

internal actual suspend fun HttpClient.prepareConnect(url: String, params: ConnectParams) = params
