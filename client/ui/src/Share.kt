package kinuvo.ui

import androidx.compose.runtime.Composable

/** Opens the platform's share sheet with some text; null where there isn't one. */
@Composable
internal expect fun rememberShare(): ((String) -> Unit)?

/** Hands the file at a path to the platform's share sheet, or shows it in the file manager on desktop; null in the browser. */
@Composable
internal expect fun rememberShareFile(): ((String) -> Unit)?
