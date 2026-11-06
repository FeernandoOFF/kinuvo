package kinuvo.ui.session

import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.sound.sampled.SourceDataLine
import javax.sound.sampled.TargetDataLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JvmLiveAudioTest {
    private class Processing : LiveAudioProcessing {
        val disposed = CountDownLatch(1)
        val reverse = AtomicInteger()
        var onPlayback: () -> Unit = {}
        override fun capture(frame: ByteArray) { check(disposed.count != 0L) }
        override fun playback(frame: ByteArray) {
            check(disposed.count != 0L)
            reverse.incrementAndGet()
            onPlayback()
            check(disposed.count != 0L)
        }
        override fun dispose() { disposed.countDown() }
    }

    private class Lines {
        val micClosed = CountDownLatch(1)
        val outputClosed = CountDownLatch(1)
        val writes = AtomicInteger()
        var failOutput = false
        var onWrite: () -> Unit = {}
        val mic = line(TargetDataLine::class.java) { name ->
            when (name) {
                "read" -> { micClosed.await(); -1 }
                "close" -> { micClosed.countDown(); null }
                else -> null
            }
        }
        val out = line(SourceDataLine::class.java) { name ->
            when (name) {
                "open" -> { check(!failOutput) { "No output" }; null }
                "write" -> { writes.incrementAndGet(); onWrite(); 480 }
                "close" -> { outputClosed.countDown(); null }
                "getBufferSize", "available" -> 9600
                else -> null
            }
        }
        fun audio(processing: Processing) = JvmLiveAudio({ mic }, { out }, { processing })
    }

    @Test fun closesAcquiredLinesAndProcessingWhenOutputOpenFails() {
        val lines = Lines().apply { failOutput = true }
        val processing = Processing()
        var errors = 0
        lines.audio(processing).startLive(24_000, {}, { errors++ })
        assertEquals(1, errors)
        assertEquals(0L, lines.micClosed.count)
        assertEquals(0L, lines.outputClosed.count)
        assertEquals(0L, processing.disposed.count)
    }

    @Test fun stoppingIdlePlaybackInterruptsItsQueueWait() {
        val lines = Lines()
        val processing = Processing()
        val audio = lines.audio(processing)
        audio.startLive(24_000, {}, {})
        audio.stopLive()
        assertEquals(0L, processing.disposed.count)
    }

    @Test fun flushDiscardsTheRestOfTheChunkWithoutSubtractingNewAudio() {
        val lines = Lines()
        val processing = Processing()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val second = CountDownLatch(1)
        lines.onWrite = {
            if (lines.writes.get() == 1) { entered.countDown(); release.await() }
            else second.countDown()
        }
        val audio = lines.audio(processing)
        try {
            audio.startLive(24_000, {}, {})
            audio.playLive(ByteArray(4800))
            entered.awaitTest()
            audio.flushLive()
            audio.playLive(ByteArray(480))
            release.countDown()
            second.awaitTest()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (audio.queuedMillis() != 0 && System.nanoTime() < deadline) Thread.yield()
            assertEquals(0, audio.queuedMillis())
            assertEquals(2, lines.writes.get())
            assertEquals(2, processing.reverse.get())
        } finally { release.countDown(); audio.stopLive() }
    }

    @Test fun teardownWaitsForNativeProcessingToReturn() {
        val lines = Lines()
        val processing = Processing()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        processing.onPlayback = { entered.countDown(); release.awaitUninterruptibly() }
        val audio = lines.audio(processing)
        audio.startLive(24_000, {}, {})
        audio.playLive(ByteArray(4800))
        entered.awaitTest()
        val stop = Thread { audio.stopLive() }.apply { start() }
        try {
            lines.outputClosed.awaitTest()
            assertEquals(1L, processing.disposed.count)
        } finally { release.countDown(); stop.join(5000) }
        assertFalse(stop.isAlive)
        assertEquals(0L, processing.disposed.count)
        assertEquals(1, processing.reverse.get())
        assertEquals(0, lines.writes.get())
    }

    @Test fun teardownRetainsNativeProcessingWhenWorkerDoesNotExit() {
        val lines = Lines()
        val processing = Processing()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val returned = CountDownLatch(1)
        lines.onWrite = { entered.countDown(); release.awaitUninterruptibly(); returned.countDown() }
        val audio = lines.audio(processing)
        try {
            audio.startLive(24_000, {}, {})
            audio.playLive(ByteArray(4800))
            entered.awaitTest()
            audio.stopLive()
            assertEquals(1L, processing.disposed.count)
        } finally { release.countDown() }
        returned.awaitTest()
        assertEquals(1, processing.reverse.get())
        assertEquals(1, lines.writes.get())
    }

    private fun CountDownLatch.awaitTest() = assertTrue(await(5, TimeUnit.SECONDS), "Worker timed out")
    private fun CountDownLatch.awaitUninterruptibly() {
        while (count != 0L) try { await() } catch (_: InterruptedException) {}
    }

    private companion object {
        fun <T> line(type: Class<T>, call: (String) -> Any?): T = type.cast(
            Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, _ -> call(method.name) }
        )
    }
}
