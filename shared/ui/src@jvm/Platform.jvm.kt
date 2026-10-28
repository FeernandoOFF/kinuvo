package kinuvo.ui

import java.awt.Desktop
import java.io.File
import java.net.InetAddress
import java.net.URI
import kinuvo.app.connection.*
import kinuvo.app.device.*
import kinuvo.app.session.SpeechEngine
import kinuvo.app.session.VoiceAudio
import kinuvo.client.LoopbackCallback
import kinuvo.client.oauthState
import kinuvo.protocol.McpRedirect
import kinuvo.protocol.Platform
import kinuvo.protocol.currentPlatform
import kinuvo.ui.session.DesktopSpeechEngine
import kinuvo.ui.session.DesktopVoiceAudio
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.koin.dsl.module

/** Opens the default browser and catches its return on localhost ([McpRedirect.Loopback]). */
internal class LoopbackBrowser : OAuthBrowser {
    override val redirect = McpRedirect.Loopback

    override suspend fun signIn(url: String): String? {
        val callback = LoopbackCallback.start(oauthState(url)) ?: error("Port ${McpRedirect.LOOPBACK_PORT} is in use; paste the URL the browser ends on")
        return callback.use {
            withContext(Dispatchers.IO) { browse(url) }
            it.await()
        }
    }

    private fun browse(url: String) {
        if (runCatching { Desktop.getDesktop().browse(URI(url)) }.isSuccess) return
        ProcessBuilder(if (currentPlatform == Platform.MacOS) listOf("open", url) else listOf("xdg-open", url)).start()
    }
}

/** Every key in one owner-only `desktop.json`, next to the local Server's data. */
internal class FilePreferences(dir: File) : Preferences {
    private val file = File(dir, "desktop.json")
    private val serializer = MapSerializer(String.serializer(), String.serializer())
    private var values = file.takeIf { it.isFile }?.let { runCatching { Json.decodeFromString(serializer, it.readText()) }.getOrNull() }.orEmpty()

    @Synchronized override fun get(key: String): String? = values[key]

    @Synchronized override fun set(key: String, value: String?) {
        val new = if (value == null) values - key else values + (key to value)
        if (new == values) return
        values = new
        privateWrite(file, Json.encodeToString(serializer, new))
    }
}

internal actual val platformModule = module {
    if (currentPlatform == Platform.MacOS || currentPlatform == Platform.Windows) {
        single { DesktopSpeechEngine() }
        single<SpeechEngine> { get<DesktopSpeechEngine>() }
        single<VoiceAudio> {
            if (currentPlatform == Platform.MacOS) get<DesktopSpeechEngine>() else DesktopVoiceAudio()
        }
    }
    single<DeviceReader> { DesktopDeviceReader() }
    single<DeviceCredentials> { DesktopCredentials(System.getenv("KINUVO_HOME")?.let(::File) ?: File(System.getProperty("user.home"), ".kinuvo")) }
    single<Preferences> {
        FilePreferences(System.getenv("KINUVO_HOME")?.let(::File) ?: File(System.getProperty("user.home"), ".kinuvo"))
    }
    single<OAuthBrowser> { LoopbackBrowser() }
    single { Device(runCatching { InetAddress.getLocalHost().hostName }.getOrDefault("desktop"), java.util.TimeZone.getDefault().id) }
}
