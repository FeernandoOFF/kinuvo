package kinuvo.server.plugin

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.io.IOException
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileModificationDate
import platform.Foundation.NSFileType
import platform.Foundation.NSFileTypeDirectory
import platform.Foundation.NSProcessInfo
import platform.Foundation.timeIntervalSince1970
import platform.posix.O_CREAT
import platform.posix.O_EXCL
import platform.posix.O_WRONLY
import platform.posix.close
import platform.posix.open
import platform.posix.rmdir
import platform.posix.unlink

@OptIn(ExperimentalForeignApi::class)
actual fun deleteTree(dir: Path) {
    // Foundation, not POSIX `lstat`, which failed for every path on iOS and made this a silent no-op.
    val isDir = (NSFileManager.defaultManager.attributesOfItemAtPath(dir.toString(), null) ?: return)[NSFileType] == NSFileTypeDirectory
    if (isDir) SystemFileSystem.list(dir).forEach(::deleteTree)
    if ((if (isDir) rmdir(dir.toString()) else unlink(dir.toString())) != 0) throw IOException("Can't delete $dir")
}

@OptIn(ExperimentalForeignApi::class)
actual fun createPrivateFile(file: Path) {
    val fd = open(file.toString(), O_CREAT or O_EXCL or O_WRONLY, (6 shl 6).toUShort())
    if (fd < 0) throw IOException("Can't create $file")
    close(fd)
}

actual fun processEnv(): Map<String, String> =
    NSProcessInfo.processInfo.environment.entries.associate { (k, v) -> k.toString() to v.toString() }

/** Through Foundation: the POSIX `stat` call failed for every file on iOS, so nothing could tell a file changed. */
@OptIn(ExperimentalForeignApi::class)
actual fun Path.modifiedNanos(): Long? =
    (NSFileManager.defaultManager.attributesOfItemAtPath(toString(), null)?.get(NSFileModificationDate) as? NSDate)
        ?.let { (it.timeIntervalSince1970 * 1_000_000_000.0).toLong() }
