package kinuvo.ui

import kinuvo.app.connection.*
import kinuvo.app.device.*

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.bind
import org.koin.dsl.module

private class SharedPreferencesStore(context: Context) : Preferences {
    private val prefs = context.getSharedPreferences("kinuvo", Context.MODE_PRIVATE)

    override fun get(key: String): String? = prefs.getString(key, null)

    override fun set(key: String, value: String?) {
        prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.commit().also { check(it) { "Couldn't save preferences" } }
    }
}

/** A light tick. */
private class HapticNotifier(context: Context) : Notifier {
    private val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator

    override fun notify(notice: Notice) {
        if (vibrator?.hasVibrator() != true) return
        when {
            Build.VERSION.SDK_INT >= 29 -> vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
            Build.VERSION.SDK_INT >= 26 -> vibrator.vibrate(VibrationEffect.createOneShot(20, 60))
            else -> @Suppress("DEPRECATION") vibrator.vibrate(20)
        }
    }
}

internal actual val platformModule = module {
    single<EmbeddedServer> { AndroidEmbeddedServer(androidContext()) }
    single<DeviceReader> { AndroidContactsReader(androidContext()) }
    single<kinuvo.app.computer.ComputerDriver> { AndroidComputer(androidContext()) }
    single { HapticNotifier(androidContext()) } bind Notifier::class
    single { androidTestMode(androidContext()) }
    single<DeviceCredentials> { get<MobileTestMode>().takeIf { it.override != null }?.credentials ?: AndroidCredentials(androidContext()) }
    single<Preferences> { get<MobileTestMode>().takeIf { it.override != null }?.preferences ?: SharedPreferencesStore(androidContext()) }
    single<OAuthBrowser> {
        val context = androidContext()
        AppSchemeBrowser { url ->
            CustomTabsIntent.Builder().build().apply { intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }.launchUrl(context, Uri.parse(url))
        }
    }
    single { Device("${Build.MANUFACTURER} ${Build.MODEL}", java.util.TimeZone.getDefault().id, get<MobileTestMode>().override?.websocketUrl.orEmpty()) }
}
