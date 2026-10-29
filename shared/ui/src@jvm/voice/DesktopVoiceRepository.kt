package kinuvo.ui.voice

import kinuvo.app.connection.Preferences
import kinuvo.app.voice.*
import kinuvo.voice.ModelStore
import kinuvo.voice.VoicePreferences
import java.io.File

/** Retained for legacy Device settings; chat uses the selected Server instead. */
internal class DesktopVoiceRepository(root: File, preferences: Preferences) : LocalVoiceRepository {
    private val store = ModelStore(root, object : VoicePreferences {
        override fun get(key: String) = preferences.get(key)
        override fun set(key: String, value: String?) = preferences.set(key, value)
    })
    override val models get() = store.models
    override var language: String
        get() = store.language
        set(value) { store.language = value }
    override fun selected(kind: VoiceModelKind) = store.selected(kind)
    override fun select(kind: VoiceModelKind, id: String?) = store.select(kind, id)
    override fun installed() = store.installed()
    override suspend fun download(model: VoiceModel, progress: (Long) -> Unit) = store.download(model, progress)
    override suspend fun remove(model: VoiceModel) = store.remove(model)
    fun acquire(model: VoiceModel) = store.acquire(model)
    fun release(model: VoiceModel) = store.release(model)
    fun directory(model: VoiceModel) = store.directory(model)
    internal fun receipt(model: VoiceModel, folder: File) = store.receipt(model, folder)
}
