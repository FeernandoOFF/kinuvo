package kinuvo.app.session

import kinuvo.app.connection.Preferences
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AiConsentTest {
    private class MemoryPreferences : Preferences {
        private val values = mutableMapOf<String, String>()
        override fun get(key: String) = values[key]
        override fun set(key: String, value: String?) { if (value == null) values.remove(key) else values[key] = value }
        override fun clear() { values.clear() }
    }

    @Test fun localInferenceDoesNotAskToSendDataToThirdParties() {
        val consent = AiConsent(MemoryPreferences(), true)
        assertFalse(consent.needed("local"))
        assertFalse(consent.needed("apple"))
        assertTrue(consent.needed("openai"))
    }

    @Test fun grantingOneProviderDoesNotAuthorizeAnother() {
        val preferences = MemoryPreferences()
        AiConsent(preferences, true).grant("openai")
        val afterRestart = AiConsent(preferences, true)
        assertFalse(afterRestart.needed("openai"))
        assertTrue(afterRestart.needed("custom/private-server"))
    }
}
