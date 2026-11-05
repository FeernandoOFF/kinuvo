package kinuvo.harness.server
import kinuvo.harness.server.devices.PeerRegistry

import kinuvo.harness.core.plugin.Notification

/** Pushes Notifications to every paired Device that subscribed, through its Web Push endpoint. */
interface DevicePush : AutoCloseable {
    /** Returns how many Devices took it. */
    suspend fun send(notification: Notification): Int
}

/** Null where this Server can't encrypt Web Push messages; pushes say they're from [serverId]. */
internal expect fun devicePush(peers: PeerRegistry, serverId: String): DevicePush?
