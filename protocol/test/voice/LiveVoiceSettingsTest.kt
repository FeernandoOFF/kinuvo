package kinuvo.protocol.voice

import kinuvo.protocol.ProtocolJson
import kotlin.test.*

class LiveVoiceSettingsTest {
    @Test fun defaultsOnWithoutClaimingAvailabilityAndPreservesExplicitOff() {
        val defaults = ProtocolJson.decodeFromString(LiveVoiceSettings.serializer(), "{}")
        assertTrue(defaults.enabled)
        assertFalse(defaults.available)
        val disabled = LiveVoiceSettings(available = true, enabled = false)
        assertEquals(disabled, ProtocolJson.decodeFromString(LiveVoiceSettings.serializer(), ProtocolJson.encodeToString(LiveVoiceSettings.serializer(), disabled)))
    }

    @Test fun settingsFromAServerWithoutAFixDecodeAndOneWithAFixRoundTrips() {
        val older = ProtocolJson.decodeFromString(LiveVoiceSettings.serializer(), """{"available":true,"enabled":false}""")
        assertEquals(LiveVoiceSettings(available = true, enabled = false), older)
        assertNull(older.unavailable)
        val needsSignIn = LiveVoiceSettings(unavailable = LiveUnavailable("Needs a device code.", "codex"))
        assertEquals(needsSignIn, ProtocolJson.decodeFromString(LiveVoiceSettings.serializer(), ProtocolJson.encodeToString(LiveVoiceSettings.serializer(), needsSignIn)))
        val newer = ProtocolJson.decodeFromString(LiveVoiceSettings.serializer(), """{"available":false,"unavailable":{"reason":"r","signInProvider":"codex","later":1}}""")
        assertEquals("codex", newer.unavailable?.signInProvider)
    }
}
