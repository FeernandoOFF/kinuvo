package kinuvo.ui

import kinuvo.app.connection.*
import kinuvo.app.device.*
import kinuvo.app.session.*

import org.koin.dsl.bind
import org.koin.dsl.binds
import org.koin.dsl.module
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSTimeZone
import platform.Foundation.NSUserDomainMask
import platform.Foundation.NSUserDefaults
import platform.Foundation.localTimeZone
import platform.UIKit.UIDevice
import platform.UIKit.UIImpactFeedbackGenerator
import platform.UIKit.UIImpactFeedbackStyle
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

private class UserDefaultsStore : Preferences {
    private val defaults = NSUserDefaults.standardUserDefaults

    override fun get(key: String): String? = defaults.stringForKey("kinuvo.$key")

    override fun set(key: String, value: String?) {
        if (value == null) defaults.removeObjectForKey("kinuvo.$key") else defaults.setObject(value, "kinuvo.$key")
    }

    override fun clear() {
        defaults.dictionaryRepresentation().keys.filterIsInstance<String>().filter { it.startsWith("kinuvo.") }.forEach(defaults::removeObjectForKey)
    }
}

/** A light tap. */
private class HapticNotifier : Notifier {
    // Notices come from background dispatchers; UIKit needs the main thread.
    override fun notify(notice: Notice) = dispatch_async(dispatch_get_main_queue()) {
        UIImpactFeedbackGenerator(UIImpactFeedbackStyle.UIImpactFeedbackStyleLight).impactOccurred()
    }
}

internal actual val platformModule = module {
    single { iosTestMode() }
    single<DeviceReader> { IosDeviceReader() }
    single<SpeechEngine> { checkNotNull(nativeSpeech) }
    single { HapticNotifier() } bind Notifier::class
    single<DeviceCredentials> { get<MobileTestMode>().takeIf { it.override != null }?.credentials ?: IosCredentials() }
    single<Preferences> { get<MobileTestMode>().takeIf { it.override != null }?.preferences ?: UserDefaultsStore() }
    single<ClientCache> {
        if (get<MobileTestMode>().override != null) ClientCache.None
        else deviceCache(NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true).first() as String + "/client-cache")
    }
    single<OAuthBrowser> { WebAuthBrowser() }
    single { ApnsPushChannel(enabled = get<MobileTestMode>().override == null) } binds arrayOf(PushChannel::class, NotificationPermission::class)
    single { Device(UIDevice.currentDevice.name, NSTimeZone.localTimeZone.name, get<MobileTestMode>().override?.websocketUrl.orEmpty()) }
}
