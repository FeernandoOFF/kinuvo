package kinuvo.ui.design

import androidx.compose.runtime.compositionLocalOf

/** What Esc inside a native web page does, e.g. leaving full screen; null leaves Esc to the page. */
val LocalWebEscape = compositionLocalOf<(() -> Unit)?> { null }

/** Scrolls the list around an inline web page by a wheel delta, in CSS pixels, that the page itself didn't use. */
val LocalWebWheel = compositionLocalOf<((Float) -> Unit)?> { null }

/** Sends Esc the page left unhandled to the app; the native host relays `kinuvo:escape` from its own document. */
internal const val ESCAPE_RELAY = "addEventListener('keydown',function(e){if(e.key==='Escape'&&!e.defaultPrevented)parent.postMessage('kinuvo:escape','*')});"

/** Pointer moves inside a full-screen page: true near its top edge or corners, which keeps the app's controls shown, false once it leaves them. */
val LocalWebPointer = compositionLocalOf<((near: Boolean) -> Unit)?> { null }

/** The full-screen reveal area in dp, or CSS pixels inside the page: the top edge and the taller top corners. */
internal const val RevealEdgePx = 56
internal const val RevealCornerWidthPx = 160
internal const val RevealCornerHeightPx = 120

/**
 * A full-screen page leaves on Esc even when it uses Esc itself, as reveal.js does for its overview, and reports pointer
 * moves near its top edge or corners (`kinuvo:top`, at most every 200 ms) and leaving them (`kinuvo:away`).
 */
internal const val FULL_SCREEN_RELAY = "addEventListener('keydown',function(e){if(e.key==='Escape'){e.preventDefault();e.stopImmediatePropagation();parent.postMessage('kinuvo:escape','*')}},true);" +
    "(function(){var t=-1e9,a=false;addEventListener('pointermove',function(e){var x=e.clientX,y=e.clientY," +
    "n=y<$RevealEdgePx||y<$RevealCornerHeightPx&&(x<$RevealCornerWidthPx||x>innerWidth-$RevealCornerWidthPx);" +
    "if(n&&e.timeStamp-t>200){t=e.timeStamp;parent.postMessage('kinuvo:top','*')}else if(!n&&a)parent.postMessage('kinuvo:away','*');a=n},true)})();"
