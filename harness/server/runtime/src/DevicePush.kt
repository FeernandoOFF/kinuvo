package kinuvo.harness.server
import kinuvo.harness.server.devices.PeerRegistry

import kinuvo.harness.core.plugin.Notification

/** Pushes Notifications to every paired Device that subscribed, through its Web Push endpoint. */
interface DevicePush : AutoCloseable {
    suspend fun send(notification: Notification): Pushed
}

/** How many Devices took a push, and why the others didn't. */
data class Pushed(val delivered: Int, val failures: List<String> = emptyList())

/** Null where this Server can't encrypt Web Push messages; pushes say they're from [serverId]. */
internal expect fun devicePush(peers: PeerRegistry, serverId: String): DevicePush?
