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
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
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
    private var session: AudioSession? = null
    override val echoes get() = synchronized(lock) { session?.aec?.enabled != true }

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
    override fun stop() = stopLive()
    override fun onEndRequested(end: () -> Unit) { endCall = end }
    fun end(epoch: Long) { if (synchronized(lock) { session != null && generation == epoch }) endCall?.invoke() }
    val active get() = synchronized(lock) { session != null }

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

    override fun startLive(sampleRate: Int, onAudio: (ByteArray) -> Unit, onError: (String) -> Unit) {
        stopLive()
        synchronized(lock) {
            val s = AudioSession(sampleRate, manager.mode, manager.isSpeakerphoneOn, manager.isBluetoothScoOn)
            session = s
            val epoch = ++generation
            try {
                manager.mode = AudioManager.MODE_IN_COMMUNICATION
                if (Build.VERSION.SDK_INT >= 31) {
                    s.previousDevice = manager.communicationDevice
                    route()
                    s.devices = object : AudioDeviceCallback() {
                        override fun onAudioDevicesAdded(added: Array<AudioDeviceInfo>) = updateRoute(s)
                        override fun onAudioDevicesRemoved(removed: Array<AudioDeviceInfo>) = updateRoute(s)
                    }
                    manager.registerAudioDeviceCallback(s.devices, main)
                } else {
                    @Suppress("DEPRECATION")
                    val bluetooth = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
                    manager.isSpeakerphoneOn = !manager.isWiredHeadsetOn && !bluetooth
                    if (bluetooth && !s.sco) { manager.startBluetoothSco(); manager.isBluetoothScoOn = true }
                }
                context.startForegroundService(Intent(context, LiveVoiceService::class.java).putExtra("generation", epoch))
                val chunk = sampleRate / 10 * 2
                s.mic = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(chunk * 2, AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)))
                check(s.mic!!.state == AudioRecord.STATE_INITIALIZED) { "Microphone is unavailable" }
                s.aec = if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(s.mic!!.audioSessionId)?.apply { enabled = true } else null
                s.ns = if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(s.mic!!.audioSessionId)?.apply { enabled = true } else null
                s.track = AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setBufferSizeInBytes(maxOf(chunk * 2, AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT))).build()
                check(s.track!!.state == AudioTrack.STATE_INITIALIZED) { "Speaker is unavailable" }
                s.mic!!.startRecording()
                s.track!!.play()
                s.worker = Thread({
                    try {
                        val buffer = ByteArray(chunk)
                        var filled = 0
                        while (true) {
                            var captured: ByteArray? = null
                            synchronized(lock) {
                                if (session !== s) return@Thread
                                val n = s.mic!!.read(buffer, filled, buffer.size - filled, AudioRecord.READ_NON_BLOCKING)
                                check(n >= 0) { "Microphone read failed ($n)" }
                                filled += n
                                if (filled == buffer.size) { captured = buffer.copyOf(); filled = 0 }
                                val pcm = s.queue.peekFirst()
                                if (pcm != null) {
                                    val written = s.track!!.write(pcm, s.offset, pcm.size - s.offset, AudioTrack.WRITE_NON_BLOCKING)
                                    check(written >= 0) { "Speaker write failed ($written)" }
                                    s.offset += written
                                    if (s.offset == pcm.size) { s.queue.removeFirst(); s.offset = 0 }
                                }
                            }
                            captured?.let(onAudio)
                            Thread.sleep(5)
                        }
                    } catch (_: InterruptedException) {
                    } catch (e: Exception) {
                        main.post { if (synchronized(lock) { session === s }) onError(e.message?.let { "Call audio failed: $it" } ?: "Call audio failed") }
                    }
                }, "kinuvo-live-audio").apply { isDaemon = true; start() }
            } catch (e: Exception) {
                stopLive()
                onError(e.message?.let { "Couldn't start call audio: $it" } ?: "Live audio is unavailable")
            }
        }
    }

    override fun playLive(pcm: ByteArray) = synchronized(lock) {
        session?.let { s ->
            require(pcm.size % 2 == 0) { "Invalid PCM16 audio" }
            s.queue.addLast(pcm.copyOf())
            s.frames += pcm.size / 2
        }
        Unit
    }

    override fun queuedMillis(): Int = synchronized(lock) {
        val s = session ?: return 0
        val head = s.track?.playbackHeadPosition?.toLong()?.and(0xffffffffL) ?: 0
        s.played += (head - s.head) and 0xffffffffL
        s.head = head
        ((s.frames - s.played).coerceAtLeast(0) * 1000 / s.rate).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    override fun flushLive() = synchronized(lock) {
        session?.let { s ->
            s.queue.clear(); s.offset = 0
            s.track?.run { pause(); flush(); stop(); play() }
            s.frames = 0; s.played = 0; s.head = 0
        }
        Unit
    }

    override fun stopLive() {
        val s = synchronized(lock) {
            val current = session ?: return
            session = null
            current.devices?.let { manager.unregisterAudioDeviceCallback(it) }
            current.mic?.run { runCatching { stop() }; release() }
            current.track?.run { runCatching { pause(); flush(); stop() }; release() }
            current.aec?.release(); current.ns?.release()
            if (Build.VERSION.SDK_INT >= 31) {
                manager.clearCommunicationDevice()
                current.previousDevice?.let { manager.setCommunicationDevice(it) }
            } else {
                @Suppress("DEPRECATION")
                manager.isSpeakerphoneOn = current.speaker
                if (!current.sco && manager.isBluetoothScoOn) { manager.stopBluetoothSco(); manager.isBluetoothScoOn = false }
            }
            manager.mode = current.mode
            context.stopService(Intent(context, LiveVoiceService::class.java))
            current
        }
        s.worker?.interrupt()
    }

    private fun updateRoute(s: AudioSession) = synchronized(lock) {
        if (session === s && Build.VERSION.SDK_INT >= 31) route()
    }

    private fun route() {
        if (Build.VERSION.SDK_INT < 31) return
        val devices = manager.availableCommunicationDevices
        val external = devices.firstOrNull { it.type in setOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES) }
        (external ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER })?.let { manager.setCommunicationDevice(it) }
    }

    private class AudioSession(val rate: Int, val mode: Int, val speaker: Boolean, val sco: Boolean) {
        var mic: AudioRecord? = null
        var track: AudioTrack? = null
        var aec: AcousticEchoCanceler? = null
        var ns: NoiseSuppressor? = null
        var previousDevice: AudioDeviceInfo? = null
        var worker: Thread? = null
        var devices: AudioDeviceCallback? = null
        val queue = ArrayDeque<ByteArray>()
        var offset = 0
        var frames = 0L
        var played = 0L
        var head = 0L
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
