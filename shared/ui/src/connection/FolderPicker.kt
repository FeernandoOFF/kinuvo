package kinuvo.ui.connection

import androidx.compose.runtime.Composable

/** Opens the platform's folder picker and hands over the chosen path; null where the Server's folders can't be picked here. */
@Composable
internal expect fun rememberFolderPicker(onPicked: (String) -> Unit): (() -> Unit)?
