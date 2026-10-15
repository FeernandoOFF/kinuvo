package kinuvo.ui

import kinuvo.app.connection.*
import kinuvo.app.device.*

import kinuvo.protocol.DeviceToolResult
import kinuvo.protocol.DeviceToolStatus
import kinuvo.protocol.McpRedirect
import kinuvo.protocol.ProtocolJson
import kinuvo.protocol.Links
import kotlinx.browser.localStorage
import kotlinx.browser.window
import kotlinx.coroutines.awaitCancellation
import kotlinx.datetime.TimeZone
import org.koin.dsl.module

private class LocalStorePreferences : Preferences {
    override fun get(key: String): String? = localStorage.getItem("kinuvo.$key")

    override fun set(key: String, value: String?) {
        if (value == null) localStorage.removeItem("kinuvo.$key") else localStorage.setItem("kinuvo.$key", value)
    }
}

/** Browsers can't read the Device's contacts or health data. */
private class NoDeviceReader : DeviceReader {
    override val kinds = emptyList<String>()
    override fun authorize(kind: String, completion: (String?) -> Unit) = completion("Not available in the browser")
    override fun read(requestJson: String, completion: (String) -> Unit) =
        completion(ProtocolJson.encodeToString(DeviceToolResult.serializer(), DeviceToolResult(DeviceToolStatus.Unsupported)))
}

/**
 * A web page can't catch the sign-in's way back, so it opens the page in a new tab and waits for the URL it ends on to be
 * pasted into Settings.
 */
private class TabBrowser : OAuthBrowser {
    override val redirect = McpRedirect.Loopback

    override suspend fun signIn(url: String): String? {
        window.open(url, "_blank")
        awaitCancellation()
    }
}

internal actual val platformModule = module {
    single<DeviceReader> { NoDeviceReader() }
    single<DeviceCredentials> { BrowserCredentials() }
    single<Preferences> { LocalStorePreferences() }
    single<OAuthBrowser> { TabBrowser() }
    single {
        Device(
            "Web browser",
            runCatching { TimeZone.currentSystemDefault().id }.getOrNull(),
            "${if (window.location.protocol == "https:") "wss" else "ws"}://${window.location.host}/ws",
        )
    }
}

fun openBrowserPairingLink() {
    val url = window.location.href
    if (Links.parsePair(url) == null) return
    window.history.replaceState(null, "", url.substringBefore('#'))
    openLink(url)
}
