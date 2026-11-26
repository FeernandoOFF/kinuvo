package kinuvo.server.devices

import kinuvo.protocol.device.DeviceToolKind
import kinuvo.protocol.device.PairedDeviceInfo
import kinuvo.protocol.device.PairingCode
import kinuvo.protocol.push.PushSubscription
import kinuvo.protocol.device.ToolDevice
import kinuvo.server.plugin.TailcatInvite

interface PeerRegistry {
    val pairable: Boolean get() = false
    fun authenticate(device: String, token: String?, pairingCode: String?): String?
    fun authenticate(device: String, token: String?, pairingCode: String?, tailcatPeer: String?): String? =
        authenticate(device, token, pairingCode)
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

    /** Whether the private tunnel is serving, so [tailcatInvite] can open pairing windows. */
    val tailcatActive: Boolean get() = false
    /** Opens a one-phone window on the tunnel whose reply carries [payload]; null when the tunnel is off. */
    fun tailcatInvite(code: String, payload: String, ttlSeconds: Int, bound: Set<String>): TailcatInvite? = null
    /** The phone that used the window opened for [code]. */
    fun tailcatPhoneOf(code: String): String? = null
    /** Unpairs [phone] from the tunnel, online or not. */
    fun tailcatRevoke(phone: String) {}
    /** Calls [listener] when the tunnel turns on or off. */
    fun onTailcatChanged(listener: () -> Unit) {}

    /** Where paired Devices receive Notifications; empty when this Server can't push. */
    val pushable: Boolean get() = false
    fun setPush(token: String, subscription: PushSubscription?) {}
    fun pushSubscriptions(): List<PushSubscription> = emptyList()
    /** Forgets [endpoint] once its push service says it's gone. */
    fun dropPush(endpoint: String) {}
}
