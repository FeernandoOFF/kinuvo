package dev.kinuvo.android

import android.app.Application
import kinuvo.ui.startKinuvo
import org.koin.android.ext.koin.androidContext

class KinuvoApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        startKinuvo { androidContext(this@KinuvoApplication) }
    }
}
