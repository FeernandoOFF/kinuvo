package kinuvo.ui.diagnostics

import android.content.Context
import android.os.Build
import kinuvo.diagnostics.AppInfo
import kinuvo.diagnostics.Diagnostics
import kotlinx.io.files.Path

/** Logs and crash files under the app's files, kept when the user clears its cache. */
internal fun androidDiagnostics(context: Context): Diagnostics {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    val build = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
    return Diagnostics(
        Path(context.filesDir.path, "diagnostics"),
        AppInfo(info.versionName.orEmpty(), build.toString(), "Android", "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})", "${Build.MANUFACTURER} ${Build.MODEL}", Build.SUPPORTED_ABIS.firstOrNull().orEmpty()),
    )
}

/** Shared files go through the app's FileProvider (`attachment_paths.xml`), so they stay in the cache. */
internal fun androidLogExports(context: Context) = Path(context.cacheDir.path, "shared-logs")
