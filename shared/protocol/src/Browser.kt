package kinuvo.protocol

import kotlinx.serialization.Serializable

@Serializable
data class BrowserOwner(val agentId: String)

@Serializable
data class BrowserTab(val id: String, val agentId: String, val title: String, val url: String,
    val takeover: Boolean = false, val thumbnail: String? = null)

@Serializable
data class BrowserElement(val ref: String, val role: String, val label: String)

@Serializable
data class BrowserFrame(val tab: BrowserTab, val revision: String, val elements: List<BrowserElement> = emptyList(),
    val screenshot: String? = null, val width: Int = 1280, val height: Int = 800, val text: String = "")

@Serializable
enum class BrowserAction { Open, Capture, Navigate, Click, Type, Key, Scroll, Wait, Close, Takeover }

/** Refs are valid only with the revision returned by Capture. User input requires Takeover. */
@Serializable
data class BrowserCommand(val owner: BrowserOwner, val action: BrowserAction, val tabId: String? = null,
    val revision: String? = null, val ref: String? = null, val text: String? = null, val x: Int? = null, val y: Int? = null,
    val take: Boolean? = null)

/** What an address bar entry means: a URL as is, a bare host over HTTPS, anything else a Google search. */
fun browserAddress(typed: String): String {
    val text = typed.trim()
    val host = text.substringBefore('/').substringBefore('?').substringBefore(':')
    return when {
        text.isEmpty() -> "about:blank"
        Regex("^(https?://|about:)", RegexOption.IGNORE_CASE).containsMatchIn(text) -> text
        text.none { it.isWhitespace() } && (host == "localhost" || ('.' in host && !host.startsWith('.') && !host.endsWith('.'))) -> "https://$text"
        else -> "https://www.google.com/search?q=" + text.encodeToByteArray().joinToString("") { b ->
            val c = (b.toInt() and 0xff).toChar()
            if (c.isLetterOrDigit() && c.code < 128 || c in "-._~") c.toString() else if (c == ' ') "+" else "%" + (b.toInt() and 0xff).toString(16).uppercase().padStart(2, '0')
        }
    }
}
