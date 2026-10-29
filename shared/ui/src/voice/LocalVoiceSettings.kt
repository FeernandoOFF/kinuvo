package kinuvo.ui.voice

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.progressSemantics
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.ui.components.*
import com.composables.ui.theme.*
import kinuvo.app.voice.*
import kinuvo.ui.design.*
import org.koin.compose.getKoin

@Composable
internal fun LocalVoiceSettings() {
    val model = getKoin().getOrNull<LocalVoiceViewModel>()
    if (model == null) {
        VoiceText("Downloaded voice models are unavailable on this Device.", muted = true)
        return
    }
    val state by model.state.collectAsState()
    VoiceSettingsContent(state, model::download, model::cancel, model::remove, model::select, model::language)
}

@Composable
internal fun VoiceSettingsContent(
    state: LocalVoiceState,
    onDownload: (String) -> Unit,
    onCancel: () -> Unit,
    onRemove: (String) -> Unit,
    onSelect: (VoiceModelKind, String?) -> Unit,
    onLanguage: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(26.dp)) {
        VoiceModelGroup("Dictation model", VoiceModelKind.Transcription, state, onDownload, onCancel, onRemove, onSelect)
        VoiceSection("Language") {
            BoxWithConstraints(Modifier.padding(16.dp)) {
                val description: @Composable () -> Unit = {
                    Column(Modifier.widthIn(max = 390.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        VoiceText("Spoken language")
                        VoiceText("Used for dictation and spoken replies.", muted = true)
                    }
                }
                val picker: @Composable () -> Unit = {
                    VoiceLanguagePicker(state.language, onLanguage)
                }
                if (maxWidth < 420.dp) Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { description(); picker() }
                else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    Box(Modifier.weight(1f)) { description() }
                    picker()
                }
            }
        }
        VoiceModelGroup("Spoken replies", VoiceModelKind.Synthesis, state, onDownload, onCancel, onRemove, onSelect)
        state.error?.let { ErrorMessage(it) }
    }
}

@Composable
private fun VoiceModelGroup(
    title: String, kind: VoiceModelKind, state: LocalVoiceState,
    onDownload: (String) -> Unit, onCancel: () -> Unit, onRemove: (String) -> Unit,
    onSelect: (VoiceModelKind, String?) -> Unit,
) {
    val selected = if (kind == VoiceModelKind.Transcription) state.transcription else state.synthesis
    val models = state.models.filter { it.kind == kind }
    VoiceSection(title) {
        models.forEachIndexed { index, model ->
            if (index > 0) VoiceDivider()
            VoiceModelRow(model, state, selected == model.id, onDownload, onCancel, onRemove) { onSelect(kind, model.id) }
        }
        if (models.isNotEmpty()) VoiceDivider()
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                VoiceText("Device speech")
                VoiceText(if (kind == VoiceModelKind.Transcription) "Use each Device's speech recognition." else "Use each Device's built-in voice.", muted = true)
            }
            if (selected == null) VoiceText("In use", size = 12)
            else Button(onClick = { onSelect(kind, null) }, buttonSize = ButtonSize.Small, style = ButtonStyle.Ghost) { VoiceText("Use", size = 13) }
        }
    }
}

@Composable
private fun VoiceModelRow(
    model: VoiceModel, state: LocalVoiceState, selected: Boolean,
    onDownload: (String) -> Unit, onCancel: () -> Unit, onRemove: (String) -> Unit, onSelect: () -> Unit,
) {
    val downloading = state.downloading == model.id
    val installed = model.id in state.installed
    val available = state.downloading == null && state.removing == null
    val uri = LocalUriHandler.current
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        BoxWithConstraints {
            val compact = maxWidth < 420.dp
            val details: @Composable () -> Unit = {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    VoiceText(when (model.id) { "whisper-tiny" -> "Whisper Tiny"; "whisper-base" -> "Whisper Base"; "supertonic-3" -> "Supertonic 3"; else -> model.title })
                    VoiceText(when (model.id) {
                        "whisper-tiny" -> "Smallest download. Multilingual dictation for everyday use."
                        "whisper-base" -> "More accurate multilingual dictation. Uses more memory."
                        "supertonic-3" -> "Reads replies aloud in 31 languages."
                        else -> model.languages
                    }, muted = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        VoiceText("Download size: ${model.bytes / 1_000_000} MB", muted = true, size = 12)
                        VoiceText(model.license, Modifier.clickable { uri.openUri(model.source) }, muted = true, size = 12)
                    }
                }
            }
            val actions: @Composable () -> Unit = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    when {
                        downloading -> Button(onClick = onCancel, buttonSize = ButtonSize.Small, style = ButtonStyle.Outlined) { VoiceText("Cancel", size = 13) }
                        state.removing == model.id -> VoiceText("Removing…", muted = true, size = 12)
                        installed -> {
                            if (selected) VoiceText("In use", size = 12)
                            else Button(onClick = onSelect, enabled = available, buttonSize = ButtonSize.Small, style = ButtonStyle.Outlined) { VoiceText("Use model", size = 13) }
                            Button(onClick = { onRemove(model.id) }, enabled = available, buttonSize = ButtonSize.Small, style = ButtonStyle.Ghost) { VoiceText("Remove", muted = true, size = 13) }
                        }
                        else -> Button(onClick = { onDownload(model.id) }, enabled = available, buttonSize = ButtonSize.Small, style = ButtonStyle.Outlined) { VoiceText("↓  Download", size = 13) }
                    }
                }
            }
            if (compact) Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { details(); actions() }
            else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Box(Modifier.weight(1f)) { details() }
                actions()
            }
        }
        if (downloading) {
            val progress = (state.downloaded.toFloat() / model.bytes.coerceAtLeast(1)).coerceIn(0f, 1f)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                VoiceText("Downloading…", muted = true, size = 12)
                VoiceText("${state.downloaded / 1_000_000} / ${model.bytes / 1_000_000} MB", muted = true, size = 12)
            }
            Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(color(borderColor)).progressSemantics(progress)) {
                Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(color(accentColor)))
            }
        }
    }
}

@Composable
private fun VoiceSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        VoiceText(title, muted = true, size = 13)
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(color(panelColor)), content = content)
    }
}

@Composable
private fun VoiceDivider() { Box(Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(1.dp).background(color(borderColor).copy(alpha = 0.55f))) }

@Composable
internal fun VoiceText(text: String, modifier: Modifier = Modifier, muted: Boolean = false, size: Int = 14) {
    BasicText(text, modifier, style = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal,
        fontSize = size.sp, lineHeight = (size + 6).sp, color = color(if (muted) mutedColor else onBackgroundColor)))
}

@Composable
private fun VoiceLanguagePicker(language: String, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    DropdownMenu(expanded = open, onExpandedChange = { open = it }, panel = {
        DropdownMenuPanel {
            voiceLanguages.forEach { (code, name) ->
                DropdownMenuItem(onClick = { open = false; onSelect(code) }) { VoiceText(name) }
            }
        }
    }) {
        Button(onClick = { open = true }, style = ButtonStyle.Outlined, buttonSize = ButtonSize.Small) {
            VoiceText(voiceLanguages[language] ?: language, size = 13)
            Spacer(Modifier.width(12.dp))
            VoiceText("⌄", muted = true, size = 13)
        }
    }
}
