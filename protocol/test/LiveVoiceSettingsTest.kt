package kinuvo.protocol

import kotlin.test.*

class LiveVoiceSettingsTest {
    @Test fun defaultsOnWithoutClaimingAvailabilityAndPreservesExplicitOff() {
        val defaults = ProtocolJson.decodeFromString(LiveVoiceSettings.serializer(), "{}")
        assertTrue(defaults.enabled)
        assertFalse(defaults.available)
        val disabled = LiveVoiceSettings(available = true, enabled = false)
        assertEquals(disabled, ProtocolJson.decodeFromString(LiveVoiceSettings.serializer(), ProtocolJson.encodeToString(LiveVoiceSettings.serializer(), disabled)))
    }
}
