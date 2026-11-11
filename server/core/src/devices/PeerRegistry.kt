package kinuvo.server.devices

import kinuvo.protocol.DeviceToolKind
import kinuvo.protocol.PairedDeviceInfo
import kinuvo.protocol.PairingCode
import kinuvo.protocol.PushSubscription
import kinuvo.protocol.ToolDevice

interface PeerRegistry {
    val pairable: Boolean get() = false
    fun authenticate(device: String, token: String?, pairingCode: String?): String?
    fun valid(token: String): Boolean = true
    fun management(token: String): Boolean = true
    fun rotate(token: String): String = error("Token rotation is unavailable")
    fun toolDevices(): List<ToolDevice>
    fun setTools(token: String, tools: Set<DeviceToolKind>): String

    /** The code other Devices pair with; null when this Server can't pair them. */
    fun pairing(refresh: Boolean, management: Boolean = false): PairingCode? = null

    /** The paired Devices, marking the one holding [token]. */
    fun paired(token: String): List<PairedDeviceInfo> = emptyList()

    /** Unpairs the Device with [id]; false when there's none. */
    fun revoke(id: String): Boolean = false

    /** Where paired Devices receive Notifications; empty when this Server can't push. */
    val pushable: Boolean get() = false
    fun setPush(token: String, subscription: PushSubscription?) {}
    fun pushSubscriptions(): List<PushSubscription> = emptyList()
    /** Forgets [endpoint] once its push service says it's gone. */
    fun dropPush(endpoint: String) {}
}
