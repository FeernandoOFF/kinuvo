package kinuvo.ui

import kinuvo.ui.session.DesktopSpeechEngine
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class DesktopNativeSpeechTest {
    private val uiThread = runBlocking(Dispatchers.Main) { Thread.currentThread() }

    @Test fun nativeBridgeReportsInvalidWavOnTheUiThread() {
        val resources = System.getenv("KINUVO_TEST_NATIVE_SPEECH") ?: return
        assertTrue(File(resources, "libKinuvoSpeech.dylib").isFile, "Build the macOS speech library first")
        val previous = System.getProperty("compose.application.resources.dir")
        System.setProperty("compose.application.resources.dir", resources)
        try {
            val failed = CountDownLatch(1)
            val speech = DesktopSpeechEngine()
            runBlocking(Dispatchers.Main) {
                speech.play(byteArrayOf(1, 2, 3), { fail("Invalid WAV completed") }, {
                    assertTrue(Thread.currentThread() === uiThread)
                    failed.countDown()
                })
            }
            assertTrue(failed.await(10, TimeUnit.SECONDS), "No native event returned through the JVM bridge")
            runBlocking(Dispatchers.Main) { speech.stop() }
        } finally {
            if (previous == null) System.clearProperty("compose.application.resources.dir")
            else System.setProperty("compose.application.resources.dir", previous)
        }
    }
}
