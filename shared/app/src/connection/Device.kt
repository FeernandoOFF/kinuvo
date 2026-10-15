package kinuvo.app.connection

/** This Device as Servers see it; [timeZone] is its IANA zone, the default for the user's Routines. */
data class Device(val name: String, val timeZone: String? = null, val serverUrl: String = "")
