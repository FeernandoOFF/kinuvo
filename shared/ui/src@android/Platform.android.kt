package kinuvo.ui

import kinuvo.app.connection.*
import kinuvo.app.device.*

import kinuvo.ui.screen.AndroidBrowserRuntime
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.bind
import org.koin.dsl.binds
import org.koin.dsl.module
import java.io.File
import java.util.TimeZone
import kinuvo.app.session.SpeechEngine
import kinuvo.ui.session.AndroidSpeech

private class SharedPreferencesStore(context: Context) : Preferences {
    private val prefs = context.getSharedPreferences("kinuvo", Context.MODE_PRIVATE)

    override fun get(key: String): String? = prefs.getString(key, null)

    override fun set(key: String, value: String?) {
        prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.commit().also { check(it) { "Couldn't save preferences" } }
    }

    override fun clear() { prefs.edit().clear().commit().also { check(it) { "Couldn't clear preferences" } } }
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
    System.setProperty("kotlin-logging-to-android-native", "true")
    System.setProperty("kotlin-logging.logStartupMessage", "false")
    single { AndroidSpeech(androidContext()) } bind SpeechEngine::class
    single { AndroidBrowserRuntime(androidContext()) }
    single<EmbeddedServer> { AndroidEmbeddedServer(androidContext(), get()) }
    single<DeviceReader> { AndroidContactsReader(androidContext()) }
    single { HapticNotifier(androidContext()) } bind Notifier::class
    single { androidTestMode(androidContext()) }
    single<DeviceCredentials> { get<MobileTestMode>().takeIf { it.override != null }?.credentials ?: AndroidCredentials(androidContext()) }
    single<Preferences> { get<MobileTestMode>().takeIf { it.override != null }?.preferences ?: SharedPreferencesStore(androidContext()) }
    single<ClientCache> { if (get<MobileTestMode>().override != null) ClientCache.None else deviceCache(File(androidContext().cacheDir, "client-cache").path) }
    single { FcmPushChannel(androidContext(), get(), enabled = get<MobileTestMode>().override == null) } binds arrayOf(PushChannel::class, NotificationPermission::class)
    single<OAuthBrowser> {
        val context = androidContext()
        AppSchemeBrowser { url ->
            CustomTabsIntent.Builder().build().apply { intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }.launchUrl(context, Uri.parse(url))
        }
    }
    single { Device("${Build.MANUFACTURER} ${Build.MODEL}", TimeZone.getDefault().id, get<MobileTestMode>().override?.websocketUrl.orEmpty()) }
}
