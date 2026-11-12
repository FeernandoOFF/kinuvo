package kinuvo.app.session

import kinuvo.app.connection.Preferences
import kinuvo.protocol.surface.SurfaceAction

/** Asks once per model provider on this Device before Sessions send it messages, attachments and tool results. Apple's own models never need it. */
class AiConsent(private val prefs: Preferences, private val required: Boolean) {
    fun needed(provider: String) = required && provider !in setOf("apple", "local") && prefs["ai-consent.$provider"] == null
    fun grant(provider: String) { prefs["ai-consent.$provider"] = "1" }
}

/** A message held until the user allows [provider], an empty one when the Agent's provider isn't known yet. */
data class AiConsentRequest(val provider: String, val text: String, val voice: Boolean, val action: SurfaceAction? = null)
