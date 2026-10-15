package kinuvo.client

import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.get
import io.ktor.http.isSuccess
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalForeignApi::class)
class TlsLiveTest {
    @Test
    fun httpsAndSecureWebSocket() = runTest {
        val origin = getenv("KINUVO_TEST_TLS_ORIGIN")?.toKString()?.trimEnd('/') ?: return@runTest
        require(origin.startsWith("https://"))
        println("Checking HTTPS and secure WebSockets at $origin")
        withContext(Dispatchers.Default) {
            val client = httpClient { install(WebSockets) { maxFrameSize = MAX_FRAME_SIZE } }
            try {
                withTimeout(15_000) {
                    assertTrue(client.get(origin).status.isSuccess())
                    client.connection("wss://${origin.removePrefix("https://")}/ws").close()
                }
            } finally { client.close() }
        }
    }
}
