package kinuvo.ui

import kinuvo.ui.session.DesktopSpeechEngine
import kinuvo.ui.session.SpeechTransport
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class DesktopSpeechTest {
    private val uiThread = runBlocking(Dispatchers.Main) { Thread.currentThread() }

    private class Helper : SpeechTransport {
        private val events = ConcurrentLinkedQueue<String>()
        val commands = mutableListOf<String>()
        @Volatile private var alive = true
        override fun send(command: String) { commands.add(command) }
        override fun poll(): String? { check(alive) { "Speech connection closed" }; return events.poll() }
        override fun close() { alive = false }
        fun emit(kind: String, id: Int, text: String = "") {
            events.add("""{"event":"$kind","id":$id,"text":"$text"}""")
        }
    }

    private fun CountDownLatch.awaitCallback() = assertTrue(await(5, TimeUnit.SECONDS), "No callback from helper")

    @Test fun ignoresOldRecognitionAndAuthorizationAndCompletesSpeechOnceOnStop() {
        val helper = Helper()
        val speech = DesktopSpeechEngine { helper }
        val authorized = CountDownLatch(1)
        var completions = 0
        var transcript = ""
        val heard = CountDownLatch(1)
        runBlocking(Dispatchers.Main) {
            speech.authorize { fail("Canceled authorization was delivered") }
            speech.stop()
            speech.authorize { assertNull(it); assertTrue(Thread.currentThread() === uiThread); authorized.countDown() }
        }
        helper.emit("authorized", 1)
        helper.emit("authorized", 2)
        authorized.awaitCallback()
        runBlocking(Dispatchers.Main) {
            speech.listen(true, {}, { fail("Old recognition was delivered") }, { fail(it) })
            speech.listen(true, {}, { transcript = it; heard.countDown() }, { fail(it) })
            speech.speak("Hello") { completions++ }
            speech.stopSpeaking()
        }
        helper.emit("final", 3, "old")
        helper.emit("done", 5)
        helper.emit("final", 4, "new")
        heard.awaitCallback()
        runBlocking(Dispatchers.Main) {
            assertEquals("new", transcript)
            assertEquals(1, completions)
            speech.stop()
            helper.close()
        }
    }

    @Test fun transportFailureReleasesPendingSpeechAndReportsAnErrorOnTheUiThread() {
        val helper = Helper()
        val speech = DesktopSpeechEngine { helper }
        val failed = CountDownLatch(1)
        val done = CountDownLatch(1)
        runBlocking(Dispatchers.Main) {
            speech.listen(true, {}, {}, { assertTrue(Thread.currentThread() === uiThread); failed.countDown() })
            speech.speak("Hello") { done.countDown() }
            helper.close()
        }
        failed.awaitCallback()
        done.awaitCallback()
    }

    @Test fun synthesisFailureWithoutRecognitionUsesItsOwnCallbackAndIgnoresStaleEvents() {
        val helper = Helper()
        val speech = DesktopSpeechEngine { helper }
        val failed = CountDownLatch(1)
        var failures = 0
        var completions = 0
        runBlocking(Dispatchers.Main) {
            speech.speak("Hello", { fail("Failure must not be reported as completion") }, {
                assertEquals("Synthesis failed", it)
                assertTrue(Thread.currentThread() === uiThread)
                failures++; failed.countDown()
            })
        }
        helper.emit("error", 1, "Synthesis failed")
        failed.awaitCallback()
        runBlocking(Dispatchers.Main) { speech.speak("Next", { completions++ }, { fail(it) }) }
        helper.emit("error", 1, "Stale error")
        helper.emit("done", 1)
        runBlocking(Dispatchers.Main) {
            speech.stopSpeaking()
            assertEquals(1, failures)
            assertEquals(1, completions)
            speech.stop(); helper.close()
        }
    }

    @Test fun missingBridgeReportsAuthorizationFailure() {
        val speech = DesktopSpeechEngine { error("Bridge missing") }
        var message: String? = null
        runBlocking(Dispatchers.Main) { speech.authorize { message = it } }
        assertEquals("Bridge missing", message)
    }
}
