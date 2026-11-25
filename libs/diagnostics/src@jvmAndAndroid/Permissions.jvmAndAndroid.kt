package kinuvo.diagnostics

import kotlinx.io.files.Path
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

internal actual fun restrictToOwner(path: Path) {
    val file = java.io.File(path.toString()).toPath()
    runCatching { Files.setPosixFilePermissions(file, PosixFilePermissions.fromString(if (Files.isDirectory(file)) "rwx------" else "rw-------")) }
}
