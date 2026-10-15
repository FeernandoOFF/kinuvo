package kinuvo.harness.server

import kinuvo.protocol.DeviceToolKind

/** An embedded Server's own client ([local]) plus the Devices that paired with it over the network ([network]). */
class SharedPeers(private val local: PeerRegistry, private val network: PeerRegistry) : PeerRegistry {
    private fun owner(token: String) = if (local.valid(token)) local else network
    override val pairable get() = network.pairable
    override fun authenticate(device: String, token: String?, pairingCode: String?) =
        if (token != null && local.valid(token)) local.authenticate(device, token, null) else network.authenticate(device, token, pairingCode)
    override fun valid(token: String) = local.valid(token) || network.valid(token)
    override fun management(token: String) = owner(token).management(token)
    override fun rotate(token: String) = owner(token).rotate(token)
    override fun toolDevices() = local.toolDevices() + network.toolDevices()
    override fun setTools(token: String, tools: Set<DeviceToolKind>) = owner(token).setTools(token, tools)
    override fun pairing(refresh: Boolean, management: Boolean) = network.pairing(refresh, management)
    override fun paired(token: String) = network.paired(token)
    override fun revoke(id: String) = network.revoke(id)
}
