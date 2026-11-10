package kinuvo.harness.core

import kinuvo.server.plugin.VoiceProviders
import kinuvo.protocol.VoiceSetup
import kotlin.test.Test
import kotlin.test.assertEquals

class VoiceProvidersTest {
    @Test
    fun theDeviceDoesWhatTheServerDoesNot() {
        assertEquals(VoiceSetup(), VoiceProviders().setup)
        assertEquals(VoiceSetup(transcriber = true), VoiceProviders(transcriber = { "hi" }).setup)
    }
}
