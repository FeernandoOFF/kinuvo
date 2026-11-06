package kinuvo.ui.session

private fun localTime(millis: Double): String = js("new Date(millis).toLocaleTimeString(undefined, { hour: 'numeric', minute: '2-digit' })")

internal actual fun shortTime(at: Long): String = localTime(at.toDouble())
