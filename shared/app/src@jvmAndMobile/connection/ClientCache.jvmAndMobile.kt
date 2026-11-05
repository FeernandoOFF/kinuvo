package kinuvo.app.connection

import io.github.xxfast.kstore.file.storeOf
import kinuvo.protocol.ProtocolJson
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

actual fun deviceCache(dir: String): ClientCache {
    val root = Path(dir)
    return StoreCache(
        open = { name -> SystemFileSystem.createDirectories(root); storeOf(Path(root, "$name.json"), enableCache = false, json = ProtocolJson) },
        wipe = { if (SystemFileSystem.exists(root)) SystemFileSystem.list(root).forEach { SystemFileSystem.delete(it, mustExist = false) } },
    )
}
