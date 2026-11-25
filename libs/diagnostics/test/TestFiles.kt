package kinuvo.diagnostics

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlin.random.Random

internal fun temporaryDirectory(): Path = Path(SystemTemporaryDirectory, "kinuvo-diagnostics-${Random.nextLong().toULong()}").also { SystemFileSystem.createDirectories(it) }

internal fun Path.delete() {
    if (SystemFileSystem.metadataOrNull(this)?.isDirectory == true) SystemFileSystem.list(this).forEach { it.delete() }
    SystemFileSystem.delete(this, mustExist = false)
}

internal fun Path.write(text: String) = SystemFileSystem.sink(this).buffered().use { it.writeString(text) }

internal fun Path.read(): String = SystemFileSystem.source(this).buffered().use { it.readString() }

internal inline fun withTemporaryDirectory(block: (Path) -> Unit) {
    val dir = temporaryDirectory()
    try { block(dir) } finally { dir.delete() }
}

internal val testApp = AppInfo(version = "1.2.3", build = "45", platform = "Android", os = "Android 15", device = "Pixel 9", arch = "arm64-v8a")
