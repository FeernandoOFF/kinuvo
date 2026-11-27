package kinuvo.protocol.voice

import kinuvo.protocol.ProtocolJson
import kotlin.test.Test
import kotlin.test.assertEquals

class LiveCallTest {
    @Test fun updatesKeepTheirWireShapes() {
        fun encode(update: LiveCallUpdate) = ProtocolJson.encodeToString(LiveCallUpdate.serializer(), update)
        assertEquals("""{"type":"phase","callId":"c","phase":"Thinking"}""", encode(LiveCallUpdate.Phase("c", LiveCallPhase.Thinking)))
        assertEquals("""{"type":"transcript","callId":"c","item":"t1","text":"Hi","user":true,"final":false}""", encode(LiveCallUpdate.Transcript("c", "t1", "Hi", true, final = false)))
        assertEquals("""{"type":"ended","callId":"c","error":"The call dropped."}""", encode(LiveCallUpdate.Ended("c", "The call dropped.")))
        assertEquals(LiveCallUpdate.Ended("c"), ProtocolJson.decodeFromString(LiveCallUpdate.serializer(), """{"type":"ended","callId":"c"}"""))
    }

    @Test fun anOlderServerSetupHasNoLiveCalls() {
        assertEquals(VoiceSetup(speechToSpeech = true), ProtocolJson.decodeFromString(VoiceSetup.serializer(), """{"speechToSpeech":true}"""))
    }
}
