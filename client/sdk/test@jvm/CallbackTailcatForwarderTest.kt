package kinuvo.client

import kotlinx.coroutines.*
import kotlin.test.*

class CallbackTailcatForwarderTest {
    @Test fun openReportsThePortAndCloseStopsItOnce() = runBlocking {
        val started = mutableListOf<String>()
        val stopped = mutableListOf<Int>()
        val forwarder = CallbackTailcatForwarder({ address, done -> started += address; done(4321) }, { port, done -> stopped += port; done() })
        assertTrue(forwarder.supported)
        val forward = forwarder.open("tc-secret")
        assertEquals(listOf("tc-secret"), started)
        assertEquals(4321, forward.port)
        forward.close()
        forward.invalidate()
        assertEquals(listOf(4321), stopped)
    }

    @Test fun failedStartMapsToAShortNonSecretError() = runBlocking {
        val forwarder = CallbackTailcatForwarder({ _, done -> done(0) }, { _, done -> done() })
        val error = assertFailsWith<IllegalStateException> { forwarder.open("tc-secret") }
        assertEquals("Could not reach the Server through Tailcat", error.message)
    }

    @Test fun startCompletingAfterCancellationReleasesThePort() = runBlocking {
        var finish: ((Int) -> Unit)? = null
        val stopped = mutableListOf<Int>()
        val forwarder = CallbackTailcatForwarder({ _, done -> finish = done }, { port, done -> stopped += port; done() })
        val opening = launch(start = CoroutineStart.UNDISPATCHED) { forwarder.open("tc-secret") }
        opening.cancelAndJoin()
        finish!!(777)
        assertEquals(listOf(777), stopped)
    }

    @Test fun sharedForwarderRebuildsAfterInvalidate() = runBlocking {
        var next = 1000
        val stopped = mutableListOf<Int>()
        val shared = SharedTailcatForwarder(CallbackTailcatForwarder({ _, done -> done(next++) }, { port, done -> stopped += port; done() }))
        val first = shared.open("a")
        first.invalidate()
        val second = shared.open("a")
        assertEquals(1000, first.port)
        assertEquals(1001, second.port)
        second.close()
        assertEquals(listOf(1000, 1001), stopped)
    }
}
