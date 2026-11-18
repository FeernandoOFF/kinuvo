package kinuvo.ui

import kinuvo.protocol.device.Platform
import kinuvo.ui.session.*
import kotlin.test.*

class DesktopLinuxSpeechTest {
    @Test fun buildsCommandsWithoutShellInterpolation() {
        val text = "--hello; $(echo test)"
        assertEquals(listOf("spd-say", "-w", "-l", "pt", "--", text), linuxSpeechCommand("spd-say", text, "pt"))
        assertEquals(listOf("espeak-ng", "-v", "en", "--", text), linuxSpeechCommand("espeak-ng", text, "en"))
    }
    @Test fun linuxSelectionReportsRecognitionUnavailable() {
        desktopSpeechTransport(Platform.Linux).use { transport ->
            assertIs<LinuxSpeechTransport>(transport)
            transport.send("""{"op":"authorize","id":1}""")
            assertContains(transport.poll().orEmpty(), "downloaded Whisper")
        }
    }
    @Test fun missingProgramsGiveInstallationHint() {
        val commands = mutableListOf<List<String>>()
        LinuxSpeechTransport(run = { commands.add(it); throw java.io.IOException("missing") }, language = "fr").use { transport ->
            transport.send("""{"op":"speak","id":1,"text":"Bonjour"}""")
            val deadline = System.nanoTime() + 5_000_000_000
            var event: String? = null
            while (event == null && System.nanoTime() < deadline) { event = transport.poll(); Thread.yield() }
            assertContains(event.orEmpty(), "Install speech-dispatcher or espeak-ng")
            assertEquals(listOf("spd-say", "espeak-ng"), commands.map { it.first() })
        }
    }
    @Test fun nonMacAudioDoesNotLaunchNativeSpeech() {
        for (platform in listOf(Platform.Windows, Platform.Linux)) {
            val engine = DesktopSpeechEngine(platform = platform, launch = { error("Native helper must not launch") })
            assertTrue(engine.audioSupported)
            var authorized = false
            engine.authorizeAudio { assertNull(it); authorized = true }
            assertTrue(authorized)
            engine.stop()
        }
    }
    @Test fun captureUsesSignedLittleEndianMonoPcm16() {
        assertEquals(16000f, captureFormat.sampleRate)
        assertEquals(16, captureFormat.sampleSizeInBits)
        assertEquals(1, captureFormat.channels)
        assertFalse(captureFormat.isBigEndian)
        assertEquals(32768, pcmPeak(byteArrayOf(0, 0, 0, -128)))
        assertEquals(32767, pcmPeak(byteArrayOf(-1, 127)))
    }
}
