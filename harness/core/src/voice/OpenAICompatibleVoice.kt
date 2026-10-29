package kinuvo.harness.core.voice

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.*
import io.ktor.utils.io.readBuffer
import kotlinx.coroutines.CancellationException
import kotlinx.io.readByteArray
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable
data class VoiceConfig(
    val transcription: VoiceProviderConfig? = null,
    val synthesis: VoiceProviderConfig? = null,
)

@Serializable
data class VoiceProviderConfig(
    val type: String = "openai-compatible",
    val url: String = "https://api.openai.com/v1",
    val model: String,
    val apiKey: String? = null,
    val voice: String = "alloy",
    val language: String? = null,
)

/** One HTTP adapter for hosted and local OpenAI-compatible audio services. The caller owns [close]. */
class OpenAICompatibleVoice(http: HttpClient, private val expand: suspend (String) -> String = { it }) {
    private val client = http.config {
        followRedirects = false
        expectSuccess = false
        install(HttpTimeout) { requestTimeoutMillis = 120_000 }
    }

    fun providers(config: VoiceConfig): VoiceProviders {
        config.transcription?.let(::validate)
        config.synthesis?.let(::validate)
        return VoiceProviders(
            transcriber = config.transcription?.let { c -> Transcriber { transcribe(c, it) } },
            synthesizer = config.synthesis?.let { c -> Synthesizer { synthesize(c, it) } },
        )
    }

    fun close() = client.close()

    private fun validate(c: VoiceProviderConfig) {
        require(c.type == "openai-compatible") { "Unsupported voice provider type" }
        require(c.model.isNotBlank()) { "A voice model is required" }
        require(c.voice.isNotBlank()) { "A synthesis voice is required" }
        endpoint(c.url)
    }

    private fun endpoint(value: String): String {
        val url = runCatching { Url(value) }.getOrNull()
        require(url != null && value.startsWith("${url.protocol.name}://") && url.host.isNotBlank() &&
            (url.protocol == URLProtocol.HTTPS || url.protocol == URLProtocol.HTTP && url.host in setOf("localhost", "127.0.0.1", "::1", "[::1]")) &&
            url.user.isNullOrEmpty() && url.password.isNullOrEmpty() && '?' !in value && '#' !in value) {
            "Voice URL must use HTTPS or loopback HTTP, without credentials, query or fragment"
        }
        return value.trimEnd('/')
    }

    private suspend fun HttpRequestBuilder.authorize(c: VoiceProviderConfig) {
        c.apiKey?.let { expand(it).takeIf(String::isNotBlank)?.let { key -> bearerAuth(key) } }
    }

    private suspend fun transcribe(c: VoiceProviderConfig, audio: Audio): String {
        require(audio.format == AudioFormat.Wav) { "Transcription requires WAV audio" }
        require(audio.bytes.size in 44..MAX_RECORDING_BYTES && isWav(audio.bytes)) { "Invalid or oversized WAV recording" }
        return providerRequest("Transcription") {
            client.preparePost("${endpoint(c.url)}/audio/transcriptions") {
                authorize(c)
                setBody(MultiPartFormDataContent(formData {
                    append("model", c.model)
                    append("response_format", "json")
                    c.language?.let { append("language", it) }
                    append("file", audio.bytes, headersOf(HttpHeaders.ContentType to listOf("audio/wav"),
                        HttpHeaders.ContentDisposition to listOf("filename=\"recording.wav\"")))
                }))
            }.execute { response ->
                check(response.status.value in 200..299) { "Transcription provider returned HTTP ${response.status.value}" }
                val bytes = response.bodyAsChannel().readBuffer(128 * 1024L + 1).readByteArray()
                check(bytes.size <= 128 * 1024) { "Transcription response is too large" }
                val text = Json.parseToJsonElement(bytes.decodeToString()).jsonObject["text"]?.jsonPrimitive
                check(text != null && text.isString && text.content.length <= MAX_TRANSCRIPT_CHARS) { "Invalid transcription response" }
                text.content.trim()
            }
        }
    }

    private suspend fun synthesize(c: VoiceProviderConfig, text: String): Audio {
        require(text.isNotBlank() && text.length <= MAX_SYNTHESIS_CHARS) { "Speech text must contain 1–$MAX_SYNTHESIS_CHARS characters" }
        return providerRequest("Synthesis") {
            client.preparePost("${endpoint(c.url)}/audio/speech") {
                authorize(c)
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject {
                    put("model", c.model); put("voice", c.voice); put("input", text); put("response_format", "wav")
                }.toString())
            }.execute { response ->
                check(response.status.value in 200..299) { "Synthesis provider returned HTTP ${response.status.value}" }
                val bytes = response.bodyAsChannel().readBuffer(MAX_SPEECH_BYTES.toLong() + 1).readByteArray()
                check(bytes.size in 44..MAX_SPEECH_BYTES && isWav(bytes)) { "Invalid or oversized WAV speech response" }
                Audio(bytes, AudioFormat.Wav)
            }
        }
    }

    private suspend fun <T> providerRequest(part: String, block: suspend () -> T): T = try { block() }
    catch (e: CancellationException) { throw e }
    catch (e: Exception) {
        // Neither HTTP exception URLs nor provider response bodies may expose credentials to a Device.
        throw IllegalStateException("$part provider request failed. Check the endpoint, model and credentials.")
    }
}

const val MAX_RECORDING_BYTES = 2 * 1024 * 1024
const val MAX_SPEECH_BYTES = 8 * 1024 * 1024
const val MAX_TRANSCRIPT_CHARS = 20_000
const val MAX_SYNTHESIS_CHARS = 2_000

internal fun isWav(bytes: ByteArray) = bytes.size >= 44 && bytes.copyOfRange(0, 4).decodeToString() == "RIFF" && bytes.copyOfRange(8, 12).decodeToString() == "WAVE"
