package kinuvo.server

import kinuvo.server.plugin.LiveCallProvider
import kinuvo.server.plugin.LiveCallStart
import kinuvo.server.plugin.VoiceContext
import kinuvo.server.plugin.VoiceProviders
import kinuvo.protocol.voice.VoiceSetup
import kotlin.test.Test
import kotlin.test.assertEquals

class VoiceProvidersTest {
    @Test
    fun theDeviceDoesWhatTheServerDoesNot() {
        assertEquals(VoiceSetup(), VoiceProviders().setup)
        assertEquals(VoiceSetup(transcriber = true), VoiceProviders(transcriber = { "hi" }).setup)
        assertEquals(VoiceSetup(liveCalls = true), VoiceProviders(liveCalls = object : LiveCallProvider {
            override suspend fun available() = true
            override suspend fun start(context: VoiceContext, offerSdp: String): LiveCallStart = error("Not started")
        }).setup)
    }
}
