package kinuvo.ui.browser

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.*
import com.composables.ui.components.*
import com.composables.ui.theme.*
import kinuvo.app.browser.BrowserViewModel
import kinuvo.app.browser.BrowserState
import kinuvo.protocol.*
import kinuvo.ui.computer.ComputerControls
import kinuvo.ui.computer.ComputerDisplay
import kinuvo.ui.design.*
import kinuvo.ui.session.HeaderButton
import kinuvo.ui.session.TitleBarRow
import kinuvo.ui.session.attachmentImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.io.encoding.Base64

@Composable
fun BrowserScreen(browser: BrowserViewModel, onBack: (() -> Unit)?) {
    val state by browser.state.collectAsState()
    BrowserContent(state, BrowserActions(browser::select, browser::open, browser::navigate, browser::takeover,
        browser::closeTab, browser::tap, browser::scroll, browser::type, browser::key), onBack)
}

internal class BrowserActions(
    val select: (String) -> Unit = {}, val open: (String) -> Unit = {}, val navigate: (String) -> Unit = {},
    val takeover: (Boolean) -> Unit = {}, val closeTab: () -> Unit = {}, val tap: (Int, Int) -> Unit = { _, _ -> },
    val scroll: (Int) -> Unit = {}, val type: (String) -> Unit = {}, val key: (String) -> Unit = {},
)

@Composable
internal fun BrowserContent(state: BrowserState, browser: BrowserActions, onBack: (() -> Unit)?) {
    val url = rememberTextFieldState()
    Column(Modifier.fillMaxSize().background(color(backgroundColor))) {
        TitleBarRow(trailing = false) {
            onBack?.let { HeaderButton(Lucide.ArrowLeft, "Back", it) }
            Text("Browser", Modifier.weight(1f))
            if (state.busy) Spinner()
        }
        Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) { Input(url, "https://… or a private site") }
            Button(onClick = { browser.open(url.text.toString()) }, enabled = !state.busy, style = ButtonStyle.Outlined) { Text("New tab") }
        }
        state.error?.let { ErrorMessage(it, Modifier.padding(horizontal = 8.dp)) }
        if (state.loaded && state.tabs.isEmpty()) Text("Open a tab here or ask the Agent to browse. Logins are saved for this Agent. Take over to sign in.", Modifier.padding(16.dp))
        Row(Modifier.weight(1f).fillMaxWidth()) {
            Column(Modifier.width(126.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                state.tabs.forEach { tab ->
                    Column(Modifier.fillMaxWidth().background(color(if (tab.id == state.selected) panelColor else backgroundColor)).clickable { browser.select(tab.id) }.padding(4.dp)) {
                        Thumbnail(tab)
                        Text(tab.title.ifBlank { "New tab" }, maxLines = 2, fontSize = 12.sp)
                        Text(tab.url.substringBefore('?').take(80), maxLines = 2, fontSize = 10.sp, color = color(mutedColor))
                    }
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight().padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val tab = state.tab
                if (tab != null) {
                    Text(tab.url.substringBefore('?'), maxLines = 1, fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { browser.takeover(!tab.takeover) }, enabled = !state.busy, style = ButtonStyle.Primary) { Text(if (tab.takeover) "Hand back" else "Take over") }
                        Button(onClick = browser.closeTab, enabled = tab.takeover && !state.busy, style = ButtonStyle.Ghost) { Text("Close") }
                    }
                    val frame = state.frame?.let { f -> ComputerFrame(ComputerInfo(f.tab.id, "Browser", "Browser", true),
                        ComputerScreen(f.revision, f.width, f.height, screenshot = f.screenshot)) }
                    ComputerDisplay(frame, tab.takeover && !state.busy, browser.tap,
                        { _, y, _, toY -> browser.scroll(y - toY) }, Modifier.weight(1f).fillMaxWidth())
                    if (tab.takeover) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { browser.navigate(url.text.toString()) }, enabled = !state.busy, style = ButtonStyle.Outlined) { Text("Go to URL") }
                            Button(onClick = { browser.scroll(-500) }, enabled = !state.busy, style = ButtonStyle.Ghost) { Text("↑") }
                            Button(onClick = { browser.scroll(500) }, enabled = !state.busy, style = ButtonStyle.Ghost) { Text("↓") }
                        }
                        ComputerControls(Platform.Linux, browser.key, browser.type)
                    }
                }
            }
        }
    }
}

@Composable
private fun Thumbnail(tab: BrowserTab) {
    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, tab.thumbnail) {
        value = tab.thumbnail?.let { withContext(Dispatchers.Default) { runCatching { attachmentImage(Base64.decode(it)) }.getOrNull() } }
    }
    bitmap?.let { Image(it, "Preview of ${tab.title}", Modifier.fillMaxWidth().aspectRatio(1.6f)) }
}
