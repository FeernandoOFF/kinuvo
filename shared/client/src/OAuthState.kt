package kinuvo.client

import io.ktor.http.parseQueryString

/** The `state` of an OAuth authorization URL or of the callback URL it returns to; a callback must match its sign-in's. */
fun oauthState(url: String): String? = parseQueryString(url.substringAfter('?', "").substringBefore('#'))["state"]
