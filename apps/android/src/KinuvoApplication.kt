package dev.kinuvo.android

import android.app.Application
import dev.kinuvo.android.embedded.androidEmbeddedServerModule
import kinuvo.ui.androidComputerModule
import kinuvo.ui.startKinuvo
import org.koin.android.ext.koin.androidContext

class KinuvoApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        startKinuvo(androidEmbeddedServerModule, androidComputerModule) { androidContext(this@KinuvoApplication) }
    }
}
