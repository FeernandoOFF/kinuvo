package kinuvo.diagnostics

import kotlin.time.Clock
import kotlin.time.Instant

/** A clock that is [seconds] after a fixed start, so exports get distinct names. */
internal class TickingClock(private val seconds: Int) : Clock {
    override fun now(): Instant = Instant.fromEpochSeconds(1_800_000_000L + seconds)
}
