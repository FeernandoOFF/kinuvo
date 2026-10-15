package kinuvo.ui

import kinuvo.app.connection.MobileTestMode
import kinuvo.app.connection.MobileTestOverride
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.addressOf
import platform.Foundation.*

private var mobileTestsEnabled = false

/** Called by the Swift shell only in debug simulator builds. */
fun enableMobileTests() { mobileTestsEnabled = true }

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal fun iosTestMode(): MobileTestMode {
    if (!mobileTestsEnabled) return MobileTestMode(null)
    val bundle = NSBundle.mainBundle
    val config = runCatching {
        val file = bundle.pathForResource("e2e-config", "json") ?: return@runCatching null
        val text = NSString.stringWithContentsOfFile(file, NSUTF8StringEncoding, null) ?: return@runCatching null
        MobileTestOverride.read(text, bundle.bundleIdentifier.orEmpty(), (NSDate().timeIntervalSince1970 * 1000).toLong(), android = false)
    }.getOrNull()
    if (config != null) {
        val documents = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true).first() as String
        NSFileManager.defaultManager.createDirectoryAtPath(documents, true, null, null)
        config.runId.encodeToByteArray().usePinned { bytes ->
            val data = NSData.create(bytes.addressOf(0), config.runId.length.toULong())
            check(data.writeToFile("$documents/e2e-accepted-run-id", true)) { "Couldn't accept the test override" }
        }
    }
    return MobileTestMode(config)
}
