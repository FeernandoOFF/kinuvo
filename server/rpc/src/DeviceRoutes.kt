package kinuvo.server.rpc

import kinuvo.protocol.Api
import kinuvo.server.ServerError

internal fun RouteBuilder.deviceRoutes() {
    deviceOrLinked(Api.Presence) { caller.presence(it.active) }
    device(Api.DeviceTools) { caller.setDeviceTools(it.tools) }
    device(Api.RotateToken) { caller.rotateToken() }
    handle(Api.Pairing) { server.auth.pairing(it.refresh, it.management) ?: throw ServerError.Unavailable("This Server can't pair Devices; pair with one started by `kinuvo serve` or the desktop app") }
    device(Api.PairedDevices) { caller.pairedDevices() }
    device(Api.RevokeDevice) { caller.revokeDevice(it.id, requestId) }
}
