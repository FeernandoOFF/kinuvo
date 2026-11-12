package kinuvo.ui

import kinuvo.app.device.*

import androidx.compose.runtime.Composable
import kinuvo.protocol.device.DeviceToolKind

@Composable
internal expect fun rememberDeviceAuthorization(reader: DeviceReader): (DeviceToolKind, (String?) -> Unit) -> Unit
