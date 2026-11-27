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
import kinuvo.ui.design.GroupBox
import kinuvo.ui.design.RowGroup
import kinuvo.ui.design.TextStyles
import kinuvo.ui.settings.DeviceCodeSignIn
import kinuvo.protocol.model.DeviceCode
import kinuvo.protocol.voice.LiveUnavailable
import com.composables.icons.lucide.LogIn
import com.composables.ui.components.Text
import kinuvo.ui.design.color
import com.composables.ui.theme.mutedColor
import androidx.compose.ui.platform.LocalUriHandler
import com.composables.icons.lucide.ExternalLink
import com.composables.icons.lucide.Lucide
import org.koin.compose.viewmodel.koinViewModel

@Composable
internal fun ServerVoiceSettings() {
    val model = koinViewModel<ServerVoiceViewModel>()
    val state by model.state.collectAsState()
    state.live.unavailable?.let { LiveVoiceSignIn(it, state.deviceCode) { model.signIn(it.signInProvider) } }
    if (state.live.available) SwitchRow(
        "Live voice",
        state.live.enabled,
        model::live,
        subtitle = "Talk with ChatGPT's realtime voice model through your ChatGPT subscription. The call runs on this Device; the Server sets it up. Replaces the models below during Voice chat.",
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

/** Live voice needs a different sign-in: says why, and starts the device-code one. */
@Composable
internal fun LiveVoiceSignIn(unavailable: LiveUnavailable, code: DeviceCode?, onSignIn: () -> Unit) {
    RowGroup("Live voice") {
        GroupBox { Text(unavailable.reason, style = TextStyles.secondary, color = color(mutedColor)) }
        if (code != null) DeviceCodeSignIn(code, "It expires in 15 minutes.")
        else ActionRow("Sign in with a device code", Lucide.LogIn, onSignIn)
    }
}

private const val LiveCallsGuide = "https://kinuvo.feernandooff.com/guides/voice/#live-voice"
