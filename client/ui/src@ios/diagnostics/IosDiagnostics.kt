package kinuvo.ui.diagnostics

import kinuvo.diagnostics.AppInfo
import kinuvo.diagnostics.Diagnostics
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.io.files.Path
import platform.Foundation.NSBundle
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.UIKit.UIDevice
import platform.posix.uname
import platform.posix.utsname

private val caches get() = NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true).first() as String

/** Logs and crash files in the Caches folder, which iOS leaves out of backups. */
@OptIn(ExperimentalForeignApi::class)
internal fun iosDiagnostics(): Diagnostics {
    val bundle = NSBundle.mainBundle
    val machine = memScoped { alloc<utsname>().let { uname(it.ptr); it.machine.toKString() } }
    val device = UIDevice.currentDevice
    return Diagnostics(
        Path(caches, "diagnostics"),
        AppInfo(bundle.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String ?: "", bundle.objectForInfoDictionaryKey("CFBundleVersion") as? String ?: "", "iOS", "${device.systemName} ${device.systemVersion}", machine, ""),
    )
}

internal fun iosLogExports() = Path(caches, "shared-logs")
