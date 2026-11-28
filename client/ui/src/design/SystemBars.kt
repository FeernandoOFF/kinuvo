package kinuvo.ui.design

import androidx.compose.runtime.Composable

/** Hides the system bars while in composition, e.g. immersive mode on Android and the status bar on iPhone; desktop and web keep theirs. */
@Composable
expect fun HideSystemBars()
