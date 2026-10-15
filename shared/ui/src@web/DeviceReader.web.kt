package kinuvo.ui

import kinuvo.app.device.*

import androidx.compose.runtime.Composable
import kinuvo.protocol.DeviceToolKind

@Composable
internal actual fun rememberDeviceAuthorization(reader: DeviceReader): (DeviceToolKind, (String?) -> Unit) -> Unit =
    { _, completion -> completion("Not available in the browser") }
