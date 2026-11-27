package kinuvo.ui.session

import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler

/**
 * The phone's audio set up for a voice call: communication mode, audio focus and the speakerphone, switching to a wired
 * or Bluetooth headset while one is connected. [release] puts back what [start] found.
 */
internal class AudioRoute(private val manager: AudioManager, private val main: Handler) {
    private val mode = manager.mode
    @Suppress("DEPRECATION")
    private val speaker = manager.isSpeakerphoneOn
    @Suppress("DEPRECATION")
    private val sco = manager.isBluetoothScoOn
    private var previousDevice: AudioDeviceInfo? = null
    private var devices: AudioDeviceCallback? = null
    private var focus: AudioFocusRequest? = null
    private var active = false

    @Synchronized
    fun start() {
        active = true
        manager.mode = AudioManager.MODE_IN_COMMUNICATION
        focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .build()
            .also { manager.requestAudioFocus(it) }
        if (Build.VERSION.SDK_INT >= 31) {
            previousDevice = manager.communicationDevice
            route()
            devices = object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(added: Array<AudioDeviceInfo>) = update()
                override fun onAudioDevicesRemoved(removed: Array<AudioDeviceInfo>) = update()
            }.also { manager.registerAudioDeviceCallback(it, main) }
        } else {
            @Suppress("DEPRECATION")
            val bluetooth = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
            @Suppress("DEPRECATION")
            manager.isSpeakerphoneOn = !manager.isWiredHeadsetOn && !bluetooth
            @Suppress("DEPRECATION")
            if (bluetooth && !sco) { manager.startBluetoothSco(); manager.isBluetoothScoOn = true }
        }
    }

    @Synchronized
    fun release() {
        if (!active) return
        active = false
        devices?.let { manager.unregisterAudioDeviceCallback(it) }
        devices = null
        if (Build.VERSION.SDK_INT >= 31) {
            manager.clearCommunicationDevice()
            previousDevice?.let { manager.setCommunicationDevice(it) }
        } else {
            @Suppress("DEPRECATION")
            manager.isSpeakerphoneOn = speaker
            @Suppress("DEPRECATION")
            if (!sco && manager.isBluetoothScoOn) { manager.stopBluetoothSco(); manager.isBluetoothScoOn = false }
        }
        focus?.let { manager.abandonAudioFocusRequest(it) }
        focus = null
        manager.mode = mode
    }

    @Synchronized
    private fun update() {
        if (active) route()
    }

    private fun route() {
        if (Build.VERSION.SDK_INT < 31) return
        val available = manager.availableCommunicationDevices
        val external = available.firstOrNull { it.type in HEADSETS }
        (external ?: available.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER })?.let { manager.setCommunicationDevice(it) }
    }

    private companion object {
        val HEADSETS = setOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        )
    }
}
