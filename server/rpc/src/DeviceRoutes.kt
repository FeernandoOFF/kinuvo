package kinuvo.server.rpc

import kinuvo.protocol.Api
import arrow.core.raise.context.raise
import kinuvo.server.DeviceError

internal fun RouteBuilder.deviceRoutes() {
    deviceOrLinked(Api.Presence, ordered = true) { caller.presence(it.active, it.viewing) }
    device(Api.DeviceTools, ordered = true) { caller.setDeviceTools(it.tools) }
    device(Api.RotateToken, ordered = true) { caller.rotateToken() }
    handle(Api.Pairing) { server.auth.pairing(it.refresh, it.management) ?: raise(DeviceError.CannotPair) }
    device(Api.PairedDevices) { caller.pairedDevices() }
    device(Api.RevokeDevice, ordered = true) { caller.revokeDevice(it.id, requestId) }
}
