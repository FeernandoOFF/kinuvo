package dev.kinuvo.android

import android.app.Application
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import kinuvo.ui.KinuvoApp
import kinuvo.app.connection.openLink
import kinuvo.ui.startKinuvo
import org.koin.android.ext.koin.androidContext

class KinuvoApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        startKinuvo { androidContext(this@KinuvoApplication) }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        intent?.dataString?.let(::openLink)
        setContent { KinuvoApp() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.dataString?.let(::openLink)
    }
}
