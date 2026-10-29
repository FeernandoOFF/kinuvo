package kinuvo.ui.voice

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import kinuvo.ui.design.Spinner
import kinuvo.app.voice.ServerVoiceViewModel
import kinuvo.ui.design.ErrorMessage
import org.koin.compose.viewmodel.koinViewModel

@Composable
internal fun ServerVoiceSettings() {
    val model = koinViewModel<ServerVoiceViewModel>()
    val state by model.state.collectAsState()
    if (state.models.isEmpty()) {
        state.error?.let { ErrorMessage(it) } ?: Spinner()
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        VoiceText("Models run on this Server and are shared by its paired Devices. Audio is sent here for processing; recordings aren't saved.", muted = true, size = 13)
        VoiceSettingsContent(state, model::download, model::cancel, model::remove, model::select, model::language)
    }
}
