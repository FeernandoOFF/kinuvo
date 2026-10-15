package kinuvo.harness.core.store

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlin.random.Random

fun Path.exists() = SystemFileSystem.exists(this)

fun Path.isDirectory() = SystemFileSystem.metadataOrNull(this)?.isDirectory == true

fun Path.isFile() = SystemFileSystem.metadataOrNull(this)?.isRegularFile == true

fun Path.readText(): String = SystemFileSystem.source(this).buffered().use { it.readString() }

fun Path.readTextOrNull(): String? = if (isFile()) readText() else null

/** Children of a directory, sorted by name; empty when it isn't one. */
fun Path.children(): List<Path> = if (isDirectory()) SystemFileSystem.list(this).sortedBy { it.name } else emptyList()

fun Path.createDirectories() = SystemFileSystem.createDirectories(this)

fun Path.appendText(text: String) {
    parent?.createDirectories()
    SystemFileSystem.sink(this, append = true).buffered().use { it.writeString(text) }
}

/** Replaces [file] in one step, so readers never see it half-written. With [private], only the owner can read it. */
fun writeAtomically(file: Path, text: String, private: Boolean = false) {
    val dir = requireNotNull(file.parent) { "No parent folder for $file" }
    dir.createDirectories()
    val tmp = Path(dir, ".${file.name}.${Random.nextLong().toULong().toString(16)}.tmp")
    try {
        if (private) createPrivateFile(tmp)
        SystemFileSystem.sink(tmp).buffered().use { it.writeString(text) }
        SystemFileSystem.atomicMove(tmp, file)
    } finally {
        SystemFileSystem.delete(tmp, mustExist = false)
    }
}

/** Copies a file or a folder with everything in it. */
fun copyTree(from: Path, to: Path) {
    if (from.isDirectory()) {
        to.createDirectories()
        from.children().forEach { copyTree(it, Path(to, it.name)) }
    } else {
        to.parent?.createDirectories()
        SystemFileSystem.source(from).buffered().use { src -> SystemFileSystem.sink(to).buffered().use { src.transferTo(it) } }
    }
}

/** Deletes [dir] and what it contains without following symlinks: a link is removed, never its target. */
expect fun deleteTree(dir: Path)

/** Creates [file], which must not exist yet, readable and writable by its owner only from the start. */
internal expect fun createPrivateFile(file: Path)

/** Whether [target] is [root] or inside it once symlinks are resolved; paths that don't exist yet are judged by their closest existing parent. */
fun isInside(root: Path, target: Path): Boolean {
    val real = realPath(target) ?: return false
    val base = realPath(root) ?: return false
    return real == base || real.startsWith("$base/")
}

private fun realPath(path: Path): String? {
    var existing = path
    var rest = ""
    while (!existing.exists()) {
        rest = "/${existing.name}$rest"
        existing = existing.parent ?: return null
    }
    return SystemFileSystem.resolve(existing).toString().trimEnd('/') + rest
}

/** The process environment; empty where there is none. */
expect fun processEnv(): Map<String, String>
