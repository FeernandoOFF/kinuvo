package kinuvo.ui.design

import androidx.compose.runtime.compositionLocalOf

/** What Esc inside a native web page does, e.g. leaving full screen; null leaves Esc to the page. */
val LocalWebEscape = compositionLocalOf<(() -> Unit)?> { null }

/** Scrolls the list around an inline web page by a wheel delta, in CSS pixels, that the page itself didn't use. */
val LocalWebWheel = compositionLocalOf<((Float) -> Unit)?> { null }

/** Sends Esc the page left unhandled to the app; the native host relays `kinuvo:escape` from its own document. */
internal const val ESCAPE_RELAY = "addEventListener('keydown',function(e){if(e.key==='Escape'&&!e.defaultPrevented)parent.postMessage('kinuvo:escape','*')});"
