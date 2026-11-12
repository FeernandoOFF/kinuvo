package kinuvo.ui

import kinuvo.ui.session.DesktopSpeechEngine
import kinuvo.ui.session.SpeechTransport
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities
import kotlin.test.*

class DesktopSpeechTest {
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
        SwingUtilities.invokeAndWait {
            speech.authorize { fail("Canceled authorization was delivered") }
            speech.stop()
            speech.authorize { assertNull(it); assertTrue(SwingUtilities.isEventDispatchThread()); authorized.countDown() }
        }
        helper.emit("authorized", 1)
        helper.emit("authorized", 2)
        authorized.awaitCallback()
        SwingUtilities.invokeAndWait {
            speech.listen(true, {}, { fail("Old recognition was delivered") }, { fail(it) })
            speech.listen(true, {}, { transcript = it; heard.countDown() }, { fail(it) })
            speech.speak("Hello") { completions++ }
            speech.stopSpeaking()
        }
        helper.emit("final", 3, "old")
        helper.emit("done", 5)
        helper.emit("final", 4, "new")
        heard.awaitCallback()
        SwingUtilities.invokeAndWait {
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
        SwingUtilities.invokeAndWait {
            speech.listen(true, {}, {}, { assertTrue(SwingUtilities.isEventDispatchThread()); failed.countDown() })
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
        SwingUtilities.invokeAndWait {
            speech.speak("Hello", { fail("Failure must not be reported as completion") }, {
                assertEquals("Synthesis failed", it)
                assertTrue(SwingUtilities.isEventDispatchThread())
                failures++; failed.countDown()
            })
        }
        helper.emit("error", 1, "Synthesis failed")
        failed.awaitCallback()
        SwingUtilities.invokeAndWait { speech.speak("Next", { completions++ }, { fail(it) }) }
        helper.emit("error", 1, "Stale error")
        helper.emit("done", 1)
        SwingUtilities.invokeAndWait {
            speech.stopSpeaking()
            assertEquals(1, failures)
            assertEquals(1, completions)
            speech.stop(); helper.close()
        }
    }

    @Test fun missingBridgeReportsAuthorizationFailure() {
        val speech = DesktopSpeechEngine { error("Bridge missing") }
        var message: String? = null
        SwingUtilities.invokeAndWait { speech.authorize { message = it } }
        assertEquals("Bridge missing", message)
    }
}
