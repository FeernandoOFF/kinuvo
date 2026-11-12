package kinuvo.server.rpc

import kinuvo.protocol.Api
import arrow.core.raise.context.raise
import kinuvo.server.DeviceError

internal fun RouteBuilder.deviceRoutes() {
    deviceOrLinked(Api.Presence) { caller.presence(it.active) }
    device(Api.DeviceTools) { caller.setDeviceTools(it.tools) }
    device(Api.RotateToken) { caller.rotateToken() }
    handle(Api.Pairing) { server.auth.pairing(it.refresh, it.management) ?: raise(DeviceError.CannotPair) }
    device(Api.PairedDevices) { caller.pairedDevices() }
    device(Api.RevokeDevice) { caller.revokeDevice(it.id, requestId) }
}
