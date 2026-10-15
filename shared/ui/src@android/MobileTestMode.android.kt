package kinuvo.ui

import android.content.Context
import android.content.pm.ApplicationInfo
import android.provider.Settings
import android.util.Base64
import kinuvo.app.connection.MobileTestMode
import kinuvo.app.connection.MobileTestOverride

internal fun androidTestMode(context: Context): MobileTestMode {
    val config = if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) runCatching {
        val encoded = Settings.Global.getString(context.contentResolver, "kinuvo_e2e_override") ?: return@runCatching null
        val text = Base64.decode(encoded, Base64.DEFAULT).decodeToString()
        MobileTestOverride.read(text, context.packageName, System.currentTimeMillis(), android = true)
    }.getOrNull() else null
    if (config != null) {
        check(Settings.Global.putString(context.contentResolver, "kinuvo_e2e_accepted_run_id", config.runId)) { "Couldn't accept the test override" }
    }
    return MobileTestMode(config)
}
