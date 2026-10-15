package kinuvo.harness.server

import kinuvo.protocol.DeviceToolKind
import kinuvo.protocol.PairedDeviceInfo
import kinuvo.protocol.PairingCode
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
}
