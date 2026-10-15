package kinuvo.harness.core.store

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.io.IOException
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import platform.Foundation.NSProcessInfo
import platform.posix.S_IFDIR
import platform.posix.S_IFMT
import platform.posix.O_CREAT
import platform.posix.O_EXCL
import platform.posix.O_WRONLY
import platform.posix.close
import platform.posix.open
import platform.posix.lstat
import platform.posix.rmdir
import platform.posix.stat
import platform.posix.unlink

@OptIn(ExperimentalForeignApi::class)
actual fun deleteTree(dir: Path) {
    val isDir = memScoped {
        val st = alloc<stat>()
        if (lstat(dir.toString(), st.ptr) != 0) return
        (st.st_mode.toInt() and S_IFMT) == S_IFDIR
    }
    if (isDir) SystemFileSystem.list(dir).forEach(::deleteTree)
    if ((if (isDir) rmdir(dir.toString()) else unlink(dir.toString())) != 0) throw IOException("Can't delete $dir")
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun createPrivateFile(file: Path) {
    val fd = open(file.toString(), O_CREAT or O_EXCL or O_WRONLY, (6 shl 6).toUShort())
    if (fd < 0) throw IOException("Can't create $file")
    close(fd)
}

actual fun processEnv(): Map<String, String> =
    NSProcessInfo.processInfo.environment.entries.associate { (k, v) -> k.toString() to v.toString() }
