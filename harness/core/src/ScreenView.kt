package kinuvo.harness.core

import kinuvo.protocol.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One viewer retains one sent sample and one latest sample, independently of other viewers. */
class ScreenView(private val screen: ScreenRef, private val subscription: String, private val epoch: String,
    private val maxImageBytes: Int = 4 * 1024 * 1024) {
    private val lock = Mutex()
    private var nextFrame = 0L
    private var nextInput = 0L
    private var pending: ScreenSample? = null
    private var sent: ScreenFrame? = null
    private var displayed: ScreenFrame? = null
    private var closed = false

    suspend fun offer(sample: ScreenSample) = lock.withLock {
        if (!closed && sample.image.length <= maxImageBytes && sample.width > 0 && sample.height > 0) pending = sample
    }

    suspend fun poll(): ScreenFrame? = lock.withLock {
        if (closed || sent != null) return@withLock null
        val sample = pending ?: return@withLock null
        pending = null
        ScreenFrame(screen, subscription, epoch, ++nextFrame, sample).also { sent = it }
    }

    suspend fun ack(ack: ScreenFrameAck) = lock.withLock {
        check(!closed && matches(ack, sent)) { "Unknown frame acknowledgement" }
        displayed = sent
        sent = null
    }

    suspend fun close() = lock.withLock { closed = true; pending = null; sent = null; displayed = null }

    suspend fun input(holder: String, input: ScreenInput, gate: ScreenGate,
        apply: suspend (ComputerAction) -> Unit): ScreenInputResult = gate.input(holder, input.generation) {
        lock.withLock {
            check(!closed && matches(input.frame, displayed) && input.geometry == displayed?.sample?.geometry) { "Display a current frame before input" }
            // New geometry invalidates input even when a newer image has not reached the client yet.
            check(pending?.geometry.let { it == null || it == input.geometry } && sent?.sample?.geometry.let { it == null || it == input.geometry }) { "Screen geometry changed" }
            require(input.sequence == nextInput && input.actions.size in 1..32) { "Invalid input sequence or batch size" }
            nextInput++
            var applied = 0
            try {
                input.actions.forEach { action ->
                    val sample = requireNotNull(displayed).sample
                    action.x?.let { require(it in 0 until sample.width) }
                    action.y?.let { require(it in 0 until sample.height) }
                    action.toX?.let { require(it in 0 until sample.width) }
                    action.toY?.let { require(it in 0 until sample.height) }
                    apply(action)
                    applied++
                }
                ScreenInputResult(applied)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { ScreenInputResult(applied, "Input stopped; the next action's outcome may be unknown. Observe before continuing.") }
        }
    }

    private fun matches(ack: ScreenFrameAck, frame: ScreenFrame?) = frame != null && ack.subscription == subscription &&
        ack.epoch == epoch && ack.sequence == frame.sequence
}
