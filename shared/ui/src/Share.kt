package kinuvo.ui

import androidx.compose.runtime.Composable

/** Opens the platform's share sheet with some text; null where there isn't one. */
@Composable
internal expect fun rememberShare(): ((String) -> Unit)?
