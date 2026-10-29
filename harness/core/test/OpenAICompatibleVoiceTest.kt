package kinuvo.harness.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.http.*
import io.ktor.http.content.TextContent
import io.ktor.utils.io.*
import kinuvo.harness.core.store.*
import kinuvo.harness.core.voice.*
import kinuvo.protocol.VoiceSetup
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.io.readByteArray
import kotlinx.serialization.json.*

class OpenAICompatibleVoiceTest {
    private val wav = "RIFF".encodeToByteArray() + ByteArray(4) + "WAVE".encodeToByteArray() + ByteArray(34)
    private val config = VoiceProviderConfig(url = "http://127.0.0.1:8000/v1/", model = "local-model", apiKey = "\${VOICE_KEY}", voice = "local-voice", language = "en")

    @Test fun sendsMultipartTranscriptionAndJsonSynthesisWithIndependentModels() = runTest {
        var calls = 0
        val http = HttpClient(MockEngine { request ->
            assertEquals("Bearer private-key", request.headers[HttpHeaders.Authorization])
            when (++calls) {
                1 -> {
                    assertEquals("http://127.0.0.1:8000/v1/audio/transcriptions", request.url.toString())
                    val form = assertIs<MultiPartFormDataContent>(request.body)
                    val channel = ByteChannel(autoFlush = true)
                    val writing = async { form.writeTo(channel); channel.close() }
                    val content = channel.readBuffer().readByteArray().decodeToString()
                    writing.await()
                    for (part in listOf("name=\"model\"", "local-model", "name=\"language\"", "en", "filename=\"recording.wav\"", "audio/wav", "RIFF")) assertTrue(part in content, part)
                    respond("""{"text":" Hello there. ","usage":{"seconds":1}}""")
                }
                else -> {
                    assertEquals("http://127.0.0.1:8000/v1/audio/speech", request.url.toString())
                    val body = Json.parseToJsonElement(assertIs<TextContent>(request.body).text).jsonObject
                    assertEquals("tts-model", body["model"]!!.jsonPrimitive.content)
                    assertEquals("local-voice", body["voice"]!!.jsonPrimitive.content)
                    assertEquals("wav", body["response_format"]!!.jsonPrimitive.content)
                    assertEquals("Hello back.", body["input"]!!.jsonPrimitive.content)
                    respond(wav, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "audio/wav"))
                }
            }
        })
        val adapter = OpenAICompatibleVoice(http) { assertEquals("\${VOICE_KEY}", it); "private-key" }
        try {
            val providers = adapter.providers(VoiceConfig(config, config.copy(model = "tts-model")))
            assertEquals("Hello there.", providers.transcriber!!.transcribe(Audio(wav, AudioFormat.Wav)))
            assertContentEquals(wav, providers.synthesizer!!.synthesize("Hello back.").bytes)
            assertEquals(2, calls)
        } finally { adapter.close(); http.close() }
    }

    @Test fun keylessEndpointsAndMixedConfigurationsWork() = runTest {
        val http = HttpClient(MockEngine { request ->
            assertNull(request.headers[HttpHeaders.Authorization])
            respond("""{"text":""}""")
        })
        val adapter = OpenAICompatibleVoice(http)
        try {
            assertEquals(VoiceSetup(), adapter.providers(VoiceConfig()).setup)
            val providers = adapter.providers(VoiceConfig(transcription = config.copy(apiKey = null)))
            assertEquals(VoiceSetup(transcriber = true), providers.setup)
            assertEquals("", providers.transcriber!!.transcribe(Audio(wav, AudioFormat.Wav)))
            assertEquals(VoiceSetup(synthesizer = true), adapter.providers(VoiceConfig(synthesis = config)).setup)
        } finally { adapter.close(); http.close() }
    }

    @Test fun refusesUnsafeUrlsAndUnknownTypesBeforeRequests() {
        val http = HttpClient(MockEngine { error("Must not send") })
        val adapter = OpenAICompatibleVoice(http)
        try {
            for (url in listOf("http://remote.example/v1", "https://user:secret@example.com/v1", "https://example.com/v1?key=x", "https://example.com/#x", "https://example.com/?", "https://example.com/#", "file:///tmp/audio")) {
                assertFailsWith<IllegalArgumentException> { adapter.providers(VoiceConfig(transcription = config.copy(url = url))) }
            }
            assertFailsWith<IllegalArgumentException> { adapter.providers(VoiceConfig(synthesis = config.copy(type = "unknown"))) }
        } finally { adapter.close(); http.close() }
    }

    @Test fun boundsInputsResponsesAndDoesNotFollowRedirectsOrExposeProviderErrors() = runTest {
        var calls = 0
        var status = HttpStatusCode.Found
        var body = "secret-key and private provider details"
        val http = HttpClient(MockEngine {
            calls++
            respond(body, status, headersOf(HttpHeaders.Location, "https://elsewhere.example/stolen"))
        }) { followRedirects = true }
        val adapter = OpenAICompatibleVoice(http) { "secret-key" }
        val providers = adapter.providers(VoiceConfig(config, config))
        try {
            assertFailsWith<IllegalArgumentException> { providers.transcriber!!.transcribe(Audio(ByteArray(MAX_RECORDING_BYTES + 1), AudioFormat.Wav)) }
            assertFailsWith<IllegalArgumentException> { providers.transcriber!!.transcribe(Audio(wav, AudioFormat.Pcm16(16000))) }
            assertFailsWith<IllegalArgumentException> { providers.synthesizer!!.synthesize("x".repeat(MAX_SYNTHESIS_CHARS + 1)) }
            assertEquals(0, calls)
            val error = assertFailsWith<IllegalStateException> { providers.synthesizer!!.synthesize("hello") }
            assertFalse("secret-key" in error.message.orEmpty())
            assertEquals(1, calls)
            status = HttpStatusCode.OK
            for (invalid in listOf("{}", """{"text":42}""", """{"text":"${"x".repeat(MAX_TRANSCRIPT_CHARS + 1)}"}""", "x".repeat(128 * 1024 + 1))) {
                body = invalid
                assertFailsWith<IllegalStateException> { providers.transcriber!!.transcribe(Audio(wav, AudioFormat.Wav)) }
            }
            body = "not a wav"
            assertFailsWith<IllegalStateException> { providers.synthesizer!!.synthesize("hello") }
            body = "x".repeat(MAX_SPEECH_BYTES + 1)
            assertFailsWith<IllegalStateException> { providers.synthesizer!!.synthesize("hello") }
        } finally { adapter.close(); http.close() }
    }

    @Test fun cancellationPropagates() = runTest {
        val http = HttpClient(MockEngine { throw CancellationException("stopped") })
        val adapter = OpenAICompatibleVoice(http)
        try {
            assertFailsWith<CancellationException> { adapter.providers(VoiceConfig(synthesis = config.copy(apiKey = null))).synthesizer!!.synthesize("hello") }
        } finally { adapter.close(); http.close() }
    }

    @Test fun voiceConfigurationSurvivesOtherSettingsWrites() {
        val files = TestHome().files
        val voice = VoiceConfig(config, config.copy(model = "tts-model"))
        files.updateConfig { it.copy(voice = voice) }
        files.updateConfig { it.copy(timezone = "Europe/Dublin") }
        assertEquals(voice, files.config().voice)
        assertEquals("Europe/Dublin", files.config().timezone)
    }
}
