package kinuvo.app.connection

import io.github.xxfast.kstore.storage.storeOf
import kinuvo.protocol.ProtocolJson
import kotlinx.browser.localStorage

private const val PREFIX = "kinuvo.cache."

actual fun deviceCache(dir: String): ClientCache = StoreCache(
    open = { name -> storeOf(PREFIX + name, enableCache = false, format = ProtocolJson) },
    wipe = { (0 until localStorage.length).mapNotNull { localStorage.key(it) }.filter { it.startsWith(PREFIX) }.forEach(localStorage::removeItem) },
)
