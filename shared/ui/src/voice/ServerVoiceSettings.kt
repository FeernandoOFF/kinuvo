package kinuvo.ui.voice

import kinuvo.ui.design.Spacing
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import kinuvo.ui.design.Spinner
import kinuvo.app.voice.ServerVoiceViewModel
import kinuvo.ui.design.LoadFailure
import kinuvo.ui.design.SwitchRow
import kinuvo.ui.design.ActionRow
import androidx.compose.ui.platform.LocalUriHandler
import com.composables.icons.lucide.ExternalLink
import com.composables.icons.lucide.Lucide
import org.koin.compose.viewmodel.koinViewModel

@Composable
internal fun ServerVoiceSettings() {
    val model = koinViewModel<ServerVoiceViewModel>()
    val state by model.state.collectAsState()
    if (state.live.available) SwitchRow(
        "Live voice",
        state.live.enabled,
        model::live,
        subtitle = "Talk with the Server's speech-to-speech model through your ChatGPT subscription. Replaces the models below during Voice chat.",
    )
    if (state.live.available) {
        val uri = LocalUriHandler.current
        ActionRow("About live calls", Lucide.ExternalLink, { runCatching { uri.openUri(LiveCallsGuide) } })
    }
    if (state.models.isEmpty()) {
        state.error?.let { LoadFailure(it, onAction = model::retry) } ?: Spinner()
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.space20)) {
        VoiceText("Models run on this Server and are shared by its paired Devices. Audio is sent here for processing; recordings aren't saved.", muted = true, size = 13)
        VoiceSettingsContent(state, model::download, model::cancel, model::remove, model::select, model::language)
    }
}

private const val LiveCallsGuide = "https://feernandooff.github.io/kinuvo/guides/voice/#live-voice"
