package kinuvo.ui.session

import java.text.DateFormat
import java.util.Date

internal actual fun shortTime(at: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(at))
