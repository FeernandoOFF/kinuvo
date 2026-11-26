package kinuvo.app.session

/** A failed run told plainly: what happened, what to try, and the raw [details] for anyone who wants them. */
data class RunError(val title: String, val hint: String?, val details: String?)

private val Url = Regex("""https?://([A-Za-z0-9.-]+)""")
private val Client = Regex("""^Error from client: (\S+)""")

fun runError(message: String): RunError {
    val text = message.trim()
    val host = Url.find(text)?.groupValues?.get(1)
    val provider = Client.find(text)?.groupValues?.get(1)
    val target = host ?: provider ?: "the model provider"
    val lower = text.lowercase()
    val (title, hint) = when {
        listOf("appears to be offline", "no network route", "-1009", "network is unreachable", "unknownhost", "unable to resolve host", "nodename nor servname").any { it in lower } ->
            "Couldn't reach $target" to "This device seems to be offline. Check the connection, then send again."
        listOf("timed out", "timeout", "-1001").any { it in lower } ->
            "$target took too long to answer" to "Send again in a moment."
        listOf("connection refused", "-1004", "could not connect", "connectexception").any { it in lower } ->
            "Couldn't connect to $target" to "It may be down or blocked. Send again in a moment."
        listOf("401", "unauthorized", "invalid api key", "invalid_api_key", "incorrect api key").any { it in lower } ->
            "$target rejected the API key" to "Update the key in Settings › Model providers."
        listOf("403", "forbidden", "permission").any { it in lower } ->
            "$target refused the request" to "The account or key may lack access to this model."
        listOf("429", "rate limit", "rate_limit", "quota", "too many requests").any { it in lower } ->
            "$target is rate limiting requests" to "Wait a bit, or check the plan's usage limits."
        listOf("500", "502", "503", "504", "overloaded", "internal server error", "bad gateway", "service unavailable").any { it in lower } ->
            "$target had a problem" to "It's on their side. Send again in a moment."
        else -> return text.lineSequence().first().let { first ->
            if (first.length <= 160 && first == text) RunError(first, null, null)
            else RunError(first.take(160).let { if (first.length > 160) "$it…" else it }, null, text)
        }
    }
    return RunError(title, hint, text)
}
