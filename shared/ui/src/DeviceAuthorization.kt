package kinuvo.ui

import kinuvo.app.device.*
import kinuvo.ui.*

import androidx.compose.runtime.Composable
import kinuvo.protocol.DeviceToolKind

@Composable
internal expect fun rememberDeviceAuthorization(reader: DeviceReader): (DeviceToolKind, (String?) -> Unit) -> Unit
