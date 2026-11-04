package kinuvo.client

/**
 * A failure in words for the user. Darwin's NSError dump (`Error Domain=NSURLErrorDomain Code=-1004 "Could not connect to the
 * server." UserInfo={…}`) becomes its description, and Ktor's "Exception in http request:" prefix goes.
 */
val Throwable.userMessage: String get() = userMessage(message) ?: "Couldn't reach the Server"

fun userMessage(message: String?): String? {
    val text = message?.removePrefix("Exception in http request: ")?.trim()?.ifEmpty { null } ?: return null
    if ("Error Domain=" !in text) return text
    return NsErrorDescription.find(text)?.groupValues?.get(1)?.trim()?.removeSuffix(".") ?: "Couldn't reach the Server"
}

private val NsErrorDescription = Regex("""Code=-?\d+ "([^"]+)"""")
