package kinuvo.protocol

import kotlinx.serialization.Serializable

@Serializable
data class BrowserOwner(val agentId: String, val sessionId: String)

@Serializable
data class BrowserTab(val id: String, val agentId: String, val sessionId: String, val title: String, val url: String,
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
