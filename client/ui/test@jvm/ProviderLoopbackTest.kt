package kinuvo.ui

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.net.URI
import kotlin.test.*

class ProviderLoopbackTest {
    @Test fun catchesCallbackWithStateOnTheSelectedPort() = runBlocking {
        var redirect = ""
        val callback = providerLoopbackSignIn(
            authorize = { uri -> redirect = uri; "https://auth.test/?state=expected" },
            opened = { _, paste -> assertFalse(paste) },
            open = {
                launch(Dispatchers.IO) {
                    java.net.Socket("127.0.0.1", URI(redirect).port).use {
                        Thread.sleep(1200)
                    }
                    java.net.Socket("127.0.0.1", URI(redirect).port).use {
                        it.getOutputStream().write(("GET /" + "x".repeat(20_000)).toByteArray())
                    }
                    assertFails { URI("$redirect?state=wrong&code=bad").toURL().readText() }
                    assertTrue(URI("$redirect?state=expected&code=ok").toURL().readText().contains("Back to Kinuvo"))
                }
            },
        )
        assertEquals("127.0.0.1", URI(redirect).host)
        assertEquals("$redirect?state=expected&code=ok", callback)
    }
}
