package kinuvo.harness.server
import kinuvo.harness.server.devices.PeerRegistry

import kinuvo.harness.core.plugin.Notification

/** Pushes Notifications to every paired Device that subscribed, through its Web Push endpoint. */
interface DevicePush : AutoCloseable {
    suspend fun send(notification: Notification)
}

/** Null where this Server can't encrypt Web Push messages. */
internal expect fun devicePush(peers: PeerRegistry): DevicePush?
