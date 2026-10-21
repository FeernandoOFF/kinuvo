package dev.kinuvo.android

import android.app.Application
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import kinuvo.ui.KinuvoApp
import kinuvo.app.connection.openLink
import kinuvo.app.connection.openNewChat
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
        intent?.let(::open)
        setContent { KinuvoApp() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        open(intent)
    }

    /** Opens a link, or a launcher shortcut's new Session (res/xml/shortcuts.xml). */
    private fun open(intent: Intent) {
        if (intent.action == NEW_CHAT) openNewChat(voice = false, message = null)
        else intent.dataString?.let(::openLink)
    }

    private companion object {
        const val NEW_CHAT = "dev.kinuvo.android.NEW_CHAT"
    }
}
