package kinuvo.ui

import kinuvo.app.connection.*
import kinuvo.app.device.*

import kinuvo.client.LoopbackCallback
import kinuvo.client.oauthState
import kinuvo.protocol.McpRedirect
import kinuvo.protocol.Platform
import kinuvo.protocol.currentPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.dsl.module
import java.awt.Desktop
import java.net.URI
import java.io.File
import java.net.InetAddress

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

/** One `desktop-<key>.json` file per key, next to the local Server's data. */
internal class FilePreferences(private val dir: File) : Preferences {
    override fun get(key: String): String? = file(key).takeIf { it.isFile }?.readText()

    override fun set(key: String, value: String?) {
        if (value == null) file(key).delete() else privateWrite(file(key), value)
    }

    private fun file(key: String) = File(dir, "desktop-$key.json")
}

internal actual val platformModule = module {
    single<DeviceReader> { DesktopDeviceReader() }
    single<DeviceCredentials> { DesktopCredentials(System.getenv("KINUVO_HOME")?.let(::File) ?: File(System.getProperty("user.home"), ".kinuvo")) }
    single<Preferences> {
        FilePreferences(System.getenv("KINUVO_HOME")?.let(::File) ?: File(System.getProperty("user.home"), ".kinuvo"))
    }
    single<OAuthBrowser> { LoopbackBrowser() }
    single { Device(runCatching { InetAddress.getLocalHost().hostName }.getOrDefault("desktop"), java.util.TimeZone.getDefault().id) }
}
