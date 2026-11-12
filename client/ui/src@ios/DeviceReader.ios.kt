package kinuvo.ui

import kinuvo.app.connection.*
import kinuvo.app.device.*

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kinuvo.protocol.*

private var nativeReader: DeviceReader? = null

fun installDeviceReader(reader: DeviceReader) { nativeReader = reader }

internal class IosDeviceReader : DeviceReader {
    override val kinds get() = nativeReader?.kinds.orEmpty()
    override fun authorize(kind: String, completion: (String?) -> Unit) {
        nativeReader?.authorize(kind, completion) ?: completion("Device tools are unavailable")
    }
    override fun read(requestJson: String, completion: (String) -> Unit) {
        nativeReader?.read(requestJson, completion) ?: completion(ProtocolJson.encodeToString(DeviceToolResult.serializer(), DeviceToolResult(DeviceToolStatus.Unsupported)))
    }
}

@Composable
internal actual fun rememberDeviceAuthorization(reader: DeviceReader): (DeviceToolKind, (String?) -> Unit) -> Unit =
    remember(reader) { { kind, completion -> reader.authorize(kind.name, completion) } }
