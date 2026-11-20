package kinuvo.ui

import androidx.compose.runtime.Composable

/** Opens an HTML document in the system browser, where pages can open their own windows (such as reveal.js speaker notes); null where it can't. */
@Composable
internal expect fun rememberOpenInBrowser(): ((String) -> Unit)?
