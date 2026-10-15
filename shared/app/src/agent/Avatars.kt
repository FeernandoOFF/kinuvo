package kinuvo.app.agent

import kinuvo.protocol.AgentInfo
import kotlin.random.Random

val AvatarArtboards = listOf("Orange Blob", "Lilac Sprout", "Mint Cushion", "Coral Bean")

/** Existing Agents without a chosen face keep their seeded assignment. */
val AgentInfo.avatarArtboard: String get() = avatar?.takeIf { it in AvatarArtboards } ?: AvatarArtboards.random(Random(id.hashCode()))
