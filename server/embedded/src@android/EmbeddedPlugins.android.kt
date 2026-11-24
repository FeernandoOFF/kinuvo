package kinuvo.server.embedded

import kinuvo.plugins.browser.BrowserPlugin
import kinuvo.providers.codex.CodexAuth
import kinuvo.server.plugin.ServerPlugin
import kinuvo.server.plugin.PrivateFile
import kinuvo.server.plugin.SealedFileKeychain
import kinuvo.server.store.AgentFiles
import kinuvo.server.store.FileAttachments
import kinuvo.server.providers.ModelProviders
import kinuvo.server.providers.plus
import kinuvo.providers.litert.LiteRtProvider
import java.io.File
import java.security.KeyStore
import kotlinx.io.files.Path

/** What an Android phone's Server runs with; [extra] are plugins the app builds from its own objects, e.g. its WebView browser. */
fun embeddedPlugins(home: Path, extra: List<ServerPlugin> = emptyList()): EmbeddedPlugins {
    val files = AgentFiles(home)
    val keys = embeddedKeys(files)
    val attachments = FileAttachments(Path(home, "attachments"))::bytes
    val providers = sharedProviders(files, keys, CodexAuth(PrivateFile(Path(home, "codex-auth.json"))), attachments) + ModelProviders.of(LiteRtProvider(File(home.toString())))
    return EmbeddedPlugins(providers.plugins + sharedPlugins(files, SealedFileKeychain(Path(home, "mcp-oauth.enc"), KeystoreSealer("kinuvo-mcp-oauth"), "Android Keystore")) + extra, providers.sources)
}

/** The browser plugin over the app's WebView when this device supports it; call on the main thread. */
fun AndroidBrowserRuntime.browserPlugins(): List<ServerPlugin> = if (available) listOf(BrowserPlugin(this)) else emptyList()

/** Wipes the on-device Server's whole data directory and its Android Keystore secret; call only after the host stops. */
fun eraseEmbeddedServer(home: Path) {
    KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry("kinuvo-mcp-oauth")
    eraseEmbeddedHome(home)
}
