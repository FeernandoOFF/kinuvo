package kinuvo.desktop

import dev.onvoid.webrtc.PeerConnectionFactory
import dev.onvoid.webrtc.media.audio.CustomAudioSource
import dev.onvoid.webrtc.media.audio.HeadlessAudioDeviceModule
import kinuvo.app.connection.ConnectedServer
import kinuvo.app.connection.Notifications
import kinuvo.app.connection.Preferences
import kinuvo.app.device.DeviceAccess
import kinuvo.app.device.DeviceReader
import kinuvo.app.session.LiveAudio
import kinuvo.app.session.LiveCallPeer
import kinuvo.app.session.LiveCallVoiceChat
import kinuvo.app.session.VoicePhase
import kinuvo.protocol.agent.AgentId
import kinuvo.protocol.plugin.PluginDefinition
import kinuvo.providers.codex.CodexAuth
import kinuvo.providers.codex.CodexLiveCalls
import kinuvo.providers.codex.codexLiveHttpClient
import kinuvo.server.plugin.LiveCallProvider
import kinuvo.server.plugin.ServerPlugin
import kinuvo.testing.server.TestServer
import kinuvo.ui.session.JvmLiveCallPeer
import kinuvo.ui.session.PeerAudio
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.RandomAccessFile
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Opt-in (KINUVO_LIVE=1, macOS `say`), quota-consuming: the desktop peer and [LiveCallVoiceChat] hold a call whose Server
 * half is a real in-process Server brokering it with the ChatGPT backend. Speech is fed to the peer instead of a microphone.
 */
class LiveCallLiveTest {
    @Test
    fun deviceHeldCallThroughARealServer() {
        if (System.getenv("KINUVO_LIVE") != "1") return
        val speech = say("Ask the agent what the secret weather code for Paris is today.")
        runBlocking {
            Dispatchers.setMain(Dispatchers.Default)
            val http = codexLiveHttpClient()
            val plugin = object : ServerPlugin {
                override val definition = PluginDefinition("test.live-calls", "Live calls")
                override val liveCalls: LiveCallProvider = CodexLiveCalls(CodexAuth(), http)
            }
            val server = TestServer.start(plugins = listOf(plugin), script = { reply("The secret weather code for Paris is amber otter.") }, scenario = { agent("live", "Live Agent"); withoutDefaultAgent() })
            val module = HeadlessAudioDeviceModule()
            val factory = PeerConnectionFactory(module)
            val microphone = CustomAudioSource()
            val connected = ConnectedServer(Notifications(emptyList()), DeviceAccess(object : Preferences {
                private val values = mutableMapOf<String, String>()
                override fun get(key: String) = values[key]
                override fun set(key: String, value: String?) { if (value == null) values.remove(key) else values[key] = value }
                override fun clear() = values.clear()
            }, object : DeviceReader {
                override val kinds = emptyList<String>()
                override fun authorize(kind: String, completion: (String?) -> Unit) = completion(null)
                override fun read(requestJson: String, completion: (String) -> Unit) {}
            }))
            val client = server.client()
            try {
                withTimeout(90_000) {
                    client.connect()
                    connected.open(client, "memory://test-server")
                    val agent = AgentId("live")
                    val session = server.startSession(agent)
                    lateinit var peer: LiveCallPeer
                    val audio = object : LiveAudio {
                        override val callPeerSupported = true
                        override fun createCallPeer() = JvmLiveCallPeer { PeerAudio(factory, microphone) {} }.also { peer = it }
                        override fun authorizeAudio(completion: (String?) -> Unit) = completion(null)
                    }
                    val chat = LiveCallVoiceChat(connected, audio) { agent to session.id }
                    chat.start()
                    while (chat.state.value.phase != VoicePhase.Listening) {
                        check(chat.state.value.error == null) { chat.state.value.error.orEmpty() }
                        delay(100)
                    }
                    val pump = launch(Dispatchers.IO) {
                        var at = -24_000 * 2
                        while (isActive) {
                            val frame = if (at >= 0 && at + 480 <= speech.size) speech.copyOfRange(at, at + 480) else ByteArray(480)
                            microphone.pushAudio(frame, 16, 24_000, 1, 240)
                            at += 480
                            delay(10)
                        }
                    }
                    try {
                        var heardOutput = false
                        while (!chat.state.value.caption.contains("amber otter", ignoreCase = true)) {
                            check(chat.state.value.error == null) { chat.state.value.error.orEmpty() }
                            heardOutput = heardOutput || chat.state.value.outputLevel > 0f
                            delay(100)
                        }
                        println("LIVE heard='${chat.state.value.heard}' caption='${chat.state.value.caption}'")
                        assertTrue(chat.state.value.heard.isNotBlank(), "The model never transcribed the Device's audio")
                        chat.pause()
                        delay(500)
                        assertTrue(chat.state.value.paused)
                        chat.resume()
                        delay(500)
                        assertTrue(!chat.state.value.paused && chat.state.value.error == null)
                        chat.end()
                        delay(1_500)
                        assertTrue(chat.state.value.phase == VoicePhase.Off && chat.state.value.error == null)
                    } finally { pump.cancel() }
                }
            } finally {
                connected.close(); client.close(); server.stop(); http.close()
                Dispatchers.resetMain()
                microphone.dispose(); factory.dispose(); module.dispose()
            }
        }
    }

    private fun say(text: String): ByteArray {
        val file = File.createTempFile("live-call", ".wav")
        try {
            check(ProcessBuilder("say", "-o", file.path, "--data-format=LEI16@24000", text).start().waitFor() == 0)
            RandomAccessFile(file, "r").use { wav ->
                var at = 12L
                while (true) {
                    wav.seek(at)
                    val id = ByteArray(4).also { wav.readFully(it) }.decodeToString()
                    val size = Integer.reverseBytes(wav.readInt()).toLong()
                    if (id == "data") return ByteArray(size.toInt()).also { wav.readFully(it) }
                    at += 8 + size + size % 2
                }
            }
        } finally { file.delete() }
        error("Missing WAV data")
    }
}
