package kinuvo.ui

import kinuvo.ui.session.DesktopSpeechEngine
import java.io.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities
import kotlin.test.*

class DesktopSpeechTest {
    @Test fun synthesisReportsMissingHelperWithoutStartingRecognition() {
        val speech = DesktopSpeechEngine { error("Helper missing") }
        var error: String? = null
        var completions = 0
        SwingUtilities.invokeAndWait { speech.speak("Hello", { completions++ }, { error = it }) }
        assertEquals("Helper missing", error)
        assertEquals(1, completions)
    }

    @Test fun synthesisReportsHelperErrorsAndExitWithoutStartingRecognition() {
        for (exit in listOf(false, true)) {
            val helper = Helper()
            val speech = DesktopSpeechEngine { helper }
            val failed = CountDownLatch(1)
            var completions = 0
            SwingUtilities.invokeAndWait {
                speech.speak("Hello", { completions++ }, {
                    assertTrue(SwingUtilities.isEventDispatchThread())
                    assertTrue(it.isNotBlank())
                    failed.countDown()
                })
                if (exit) helper.destroy()
            }
            if (!exit) { helper.emit("error", 1, "SAPI synthesis failed"); helper.emit("done", 1) }
            failed.awaitCallback()
            SwingUtilities.invokeAndWait { assertEquals(1, completions); speech.stop(); if (!exit) helper.destroy() }
        }
    }

    @Test fun recordsWavAndReportsPlaybackErrorsWhileIgnoringStoppedRecordings() {
        val helper = Helper()
        val speech = DesktopSpeechEngine { helper }
        val authorized = CountDownLatch(1)
        val recorded = CountDownLatch(1)
        val failed = CountDownLatch(1)
        var done = 0
        SwingUtilities.invokeAndWait { speech.authorizeMicrophone { assertNull(it); authorized.countDown() } }
        helper.emit("authorized", 1)
        authorized.awaitCallback()
        SwingUtilities.invokeAndWait {
            assertTrue("authorizeMicrophone" in helper.commands.toString())
            speech.record(true, { assertEquals("wav", it); recorded.countDown() }, { fail(it) })
            speech.finishRecording()
        }
        helper.emit("recorded", 2, "wav")
        recorded.awaitCallback()
        SwingUtilities.invokeAndWait {
            speech.record(false, { fail("Stopped recording was delivered") }, { fail(it) })
            speech.stopRecording()
            speech.play("wav", { done++ }, { assertEquals("Cannot decode", it); failed.countDown() })
        }
        helper.emit("recorded", 3, "old")
        helper.emit("error", 4, "Cannot decode")
        helper.emit("done", 4)
        failed.awaitCallback()
        SwingUtilities.invokeAndWait { assertEquals(1, done); speech.stop(); helper.destroy() }
    }

    private class Helper : Process() {
        val events = PipedOutputStream()
        private val input = PipedInputStream(events)
        val commands = ByteArrayOutputStream()
        private var alive = true
        override fun getOutputStream(): OutputStream = commands
        override fun getInputStream(): InputStream = input
        override fun getErrorStream(): InputStream = ByteArrayInputStream(byteArrayOf())
        override fun waitFor() = 0
        override fun exitValue() = if (alive) throw IllegalThreadStateException() else 0
        override fun isAlive() = alive
        override fun destroy() { alive = false; events.close() }
        fun emit(kind: String, id: Int, text: String = "") {
            events.write("""{"event":"$kind","id":$id,"text":"$text"}
""".toByteArray())
            events.flush()
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
            helper.destroy()
        }
    }

    @Test fun helperExitReleasesPendingSpeechAndReportsAnErrorOnTheUiThread() {
        val helper = Helper()
        val speech = DesktopSpeechEngine { helper }
        val failed = CountDownLatch(1)
        val done = CountDownLatch(1)
        SwingUtilities.invokeAndWait {
            speech.listen(true, {}, {}, { assertTrue(SwingUtilities.isEventDispatchThread()); failed.countDown() })
            speech.speak("Hello") { done.countDown() }
            helper.destroy()
        }
        failed.awaitCallback()
        done.awaitCallback()
    }

    @Test fun missingHelperReportsAuthorizationFailure() {
        val speech = DesktopSpeechEngine { error("Helper missing") }
        var message: String? = null
        SwingUtilities.invokeAndWait { speech.authorize { message = it } }
        assertEquals("Helper missing", message)
    }
}
