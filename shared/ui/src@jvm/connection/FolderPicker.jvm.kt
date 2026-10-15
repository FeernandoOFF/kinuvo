package kinuvo.ui.connection

import androidx.compose.runtime.Composable

@Composable
internal actual fun rememberFolderPicker(onPicked: (String) -> Unit): (() -> Unit)? = null
