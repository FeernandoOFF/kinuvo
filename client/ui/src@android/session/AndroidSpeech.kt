package kinuvo.ui.session

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kinuvo.app.session.LiveAudio
import kinuvo.app.session.LiveCallException
import kinuvo.app.session.LiveCallPeer
import kinuvo.app.session.SpeechEngine
import org.koin.mp.KoinPlatform.getKoin
import java.util.ArrayDeque

internal class AndroidSpeech(private val context: Context) : SpeechEngine, LiveAudio {
    private val main = Handler(Looper.getMainLooper())
    private val manager = context.getSystemService(AudioManager::class.java)
    private val lock = Any()
    private var ask: ActivityResultLauncher<String>? = null
    private var permission: ((String?) -> Unit)? = null
    private var generation = 0L
    private var endCall: (() -> Unit)? = null
    private var call: CallLease? = null

    fun attach(activity: ComponentActivity) {
        val launcher = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val callback = permission
            permission = null
            callback?.invoke(if (granted) null else "Microphone access is denied. Enable it in Android Settings.")
        }
        ask = launcher
        activity.lifecycle.addObserver(LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_DESTROY && ask === launcher) {
                ask = null
                permission?.invoke("Microphone permission request was interrupted")
                permission = null
            }
        })
    }

    override fun authorizeAudio(completion: (String?) -> Unit) {
        main.post {
            if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) completion(null)
            else if (ask == null) completion("Open the app to allow microphone access")
            else if (permission != null) completion("A microphone permission request is already open")
            else { permission = completion; ask?.launch(Manifest.permission.RECORD_AUDIO) }
        }
    }

    override fun authorize(completion: (String?) -> Unit) = completion("Device dictation is not available on Android. Choose a Server with live voice.")
    override fun listen(untilPause: Boolean, onPartial: (String) -> Unit, onFinal: (String) -> Unit, onError: (String) -> Unit) = onError("Device dictation is not available on Android")
    override fun speak(text: String, onDone: () -> Unit) = onDone()
    override fun speak(text: String, onDone: () -> Unit, onError: (String) -> Unit) = onError("Device speech synthesis is not available on Android. Choose a Server with live voice.")
    override fun stopSpeaking() {}
    override fun stop() {}
    override fun onEndRequested(end: () -> Unit) { endCall = end }
    fun end(epoch: Long) { if (synchronized(lock) { call != null && generation == epoch }) endCall?.invoke() }
    val active get() = synchronized(lock) { call != null }

    override val callPeerSupported get() = true

    override fun createCallPeer(): LiveCallPeer {
        val lease = synchronized(lock) {
            call?.release()
            val route = AudioRoute(manager, main)
            CallLease(++generation, route).also { call = it }
        }
        try {
            lease.route.start()
            context.startForegroundService(Intent(context, LiveVoiceService::class.java).putExtra("generation", lease.epoch))
        } catch (e: Exception) {
            lease.release()
            throw LiveCallException("Couldn't set up audio for the call.", e)
        }
        return AndroidLiveCallPeer(context, lease::release)
    }

    /** The audio route and background service a call peer holds until it closes. */
    private inner class CallLease(val epoch: Long, val route: AudioRoute) {
        fun release() {
            synchronized(lock) { if (call !== this) return; call = null }
            route.release()
            context.stopService(Intent(context, LiveVoiceService::class.java))
        }
    }

    private var cue: AudioTrack? = null
    private var releaseCue: Runnable? = null

    override fun playCue(pcm: ByteArray, sampleRate: Int) = synchronized(lock) {
        stopCue()
        runCatching {
            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(pcm.size).build()
            cue = track
            check(track.write(pcm, 0, pcm.size) == pcm.size)
            track.play()
            val release = Runnable { synchronized(lock) { if (cue === track) stopCue() } }
            releaseCue = release
            main.postDelayed(release, pcm.size * 1000L / (sampleRate * 2) + 100)
        }.onFailure { stopCue() }
        Unit
    }

    override fun stopCue() = synchronized(lock) {
        releaseCue?.let { main.removeCallbacks(it) }; releaseCue = null
        cue?.let { runCatching { it.pause(); it.flush(); it.stop() }; it.release() }
        cue = null
    }
}

fun attachVoice(activity: ComponentActivity) = getKoin().get<AndroidSpeech>().attach(activity)

class LiveVoiceService : Service() {
    private var wake: PowerManager.WakeLock? = null
    private var generation = -1L
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val speech = getKoin().get<AndroidSpeech>()
        val epoch = intent?.getLongExtra("generation", -1L) ?: -1L
        if (intent?.action == "end") { speech.end(epoch); return START_NOT_STICKY }
        generation = epoch
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("live-voice", "Voice chat", NotificationManager.IMPORTANCE_LOW))
        val end = PendingIntent.getService(this, 0, Intent(this, LiveVoiceService::class.java).setAction("end").putExtra("generation", generation), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        startForeground(204, Notification.Builder(this, "live-voice").setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentTitle("Kinuvo voice chat").setOngoing(true).addAction(Notification.Action.Builder(null, "End", end).build()).build())
        if (!speech.active) stopSelf()
        else if (wake == null) wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "kinuvo:live-voice").apply { acquire() }
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        wake?.let { if (it.isHeld) it.release() }
        wake = null
        getKoin().get<AndroidSpeech>().end(generation)
        super.onDestroy()
    }
}
