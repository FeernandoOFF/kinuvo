package kinuvo.harness.browser

import java.net.URI
import kinuvo.protocol.browserAddress

fun browserNavigationAllowed(value: String): Boolean = runCatching {
    value == "about:blank" || URI(value).let { it.scheme?.lowercase() in setOf("http", "https") && !it.host.isNullOrBlank() && it.userInfo == null }
}.getOrDefault(false)

fun browserDestination(value: String): String = browserAddress(value).also {
    require(browserNavigationAllowed(it)) { "Browser navigation requires HTTP(S) or about:blank, without embedded credentials" }
}
