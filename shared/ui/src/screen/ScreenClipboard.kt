package kinuvo.ui.screen

import androidx.compose.runtime.Composable

@Composable
internal expect fun rememberScreenClipboard(): suspend () -> String?
