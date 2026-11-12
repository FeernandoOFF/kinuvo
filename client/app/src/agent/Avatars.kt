package kinuvo.app.agent

import kinuvo.protocol.AgentInfo
import kinuvo.protocol.SessionStatus
import kotlin.random.Random

val AvatarArtboards = listOf("Lilac Cloud", "Tangerine Drop", "Mint Pill", "Coral Bloom", "Sky Peak", "Honey Star", "Berry Orb", "Cocoa Bloop")

/** Existing Agents without a chosen face keep their seeded assignment. */
val AgentInfo.avatarArtboard: String get() = avatar?.takeIf { it in AvatarArtboards } ?: AvatarArtboards.random(Random(id.hashCode()))

/** What an avatar shows; the ordinal is the `state` input of its Avatar state machine. */
enum class AvatarState { Idle, Listening, Working, NeedsYou }

/** An Agent needs you while any Session waits on you, and works while any runs. */
fun avatarState(statuses: List<SessionStatus>) = when {
    SessionStatus.Waiting in statuses -> AvatarState.NeedsYou
    SessionStatus.Running in statuses -> AvatarState.Working
    else -> AvatarState.Idle
}
