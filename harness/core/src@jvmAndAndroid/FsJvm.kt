package kinuvo.harness.core.store

import kotlinx.io.files.Path
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermissions

actual fun deleteTree(dir: Path) {
    val path = Paths.get(dir.toString())
    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
    Files.walk(path).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
}

internal actual fun createPrivateFile(file: Path) {
    val path = Paths.get(file.toString())
    if (path.fileSystem.supportedFileAttributeViews().contains("posix")) {
        Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
    } else Files.createFile(path)
}

actual fun processEnv(): Map<String, String> = System.getenv()
