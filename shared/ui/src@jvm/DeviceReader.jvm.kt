package kinuvo.ui

import kinuvo.app.connection.*
import kinuvo.app.device.*

import androidx.compose.runtime.Composable
import kinuvo.protocol.*

internal class DesktopDeviceReader : DeviceReader {
    override val kinds = emptyList<String>()
    override fun authorize(kind: String, completion: (String?) -> Unit) = completion("This platform has no Device tools")
    override fun read(requestJson: String, completion: (String) -> Unit) = completion(ProtocolJson.encodeToString(DeviceToolResult.serializer(), DeviceToolResult(DeviceToolStatus.Unsupported)))
}

@Composable
internal actual fun rememberDeviceAuthorization(reader: DeviceReader): (DeviceToolKind, (String?) -> Unit) -> Unit {
    val computer = org.koin.compose.getKoin().getOrNull<kinuvo.app.computer.ComputerDriver>()
    return { kind, completion ->
        if (kind == DeviceToolKind.Computer) completion(if (computer?.ready == true) null else computer?.requirement ?: "This app can't control its screen")
        else reader.authorize(kind.name, completion)
    }
}
