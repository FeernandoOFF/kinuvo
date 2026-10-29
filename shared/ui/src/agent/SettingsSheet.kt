package kinuvo.ui.agent

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import com.composables.ui.theme.destructiveColor
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.composables.ui.components.*
import com.composables.ui.theme.scrimColor
import kinuvo.app.agent.SettingsViewModel
import kinuvo.app.connection.AppViewModel
import kinuvo.app.navigation.*
import kinuvo.ui.design.*
import kinuvo.ui.session.*
import com.composables.icons.lucide.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import com.composables.ui.theme.mutedColor
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Stable
internal class SettingsEdits {
    var dirty by mutableStateOf(false)
    var pending by mutableStateOf<(() -> Unit)?>(null)
    var revision by mutableStateOf(0)
    var onDiscard: () -> Unit = {}
    fun saved() { dirty = false; pending = null }
    fun discard() {
        val next = pending
        pending = null
        dirty = false
        onDiscard()
        revision++
        next?.invoke()
    }
    fun leave(action: () -> Unit) { if (dirty) pending = action else action() }
}

internal val LocalSettingsEdits = staticCompositionLocalOf<SettingsEdits?> { null }

@Composable
internal fun SettingsSheet(app: AppViewModel, pages: SnapshotStateList<Route.Settings>, onOpenSession: (Route.Chat) -> Unit) {
    val edits = remember { SettingsEdits() }
    val savedPages = rememberSaveableStateHolder()
    val back = { edits.leave { pages.removeLastOrNull(); Unit } }
    NavigationBackHandler(rememberNavigationEventState(NavigationEventInfo.None), isBackEnabled = true) {
        if (edits.pending != null) edits.pending = null else back()
    }
    CompositionLocalProvider(LocalSettingsEdits provides edits, LocalModalNavigation provides ModalNavigation(back.takeIf { pages.size > 1 })) {
        BottomSheet(true, onDismissRequest = { edits.leave { pages.clear() } }, header = {}, modifier = if (edits.pending != null) Modifier.clearAndSetSemantics {} else Modifier) {
            val route = pages.lastOrNull()
            if (route != null) savedPages.SaveableStateProvider(route.toString()) {
                DisposableEffect(route) {
                    edits.onDiscard = { savedPages.removeState(route.toString()) }
                    onDispose { edits.onDiscard = {} }
                }
                val settings = rememberSettingsViewModel(route)
                SettingsScreen(app, settings, onNavigate = { next, push -> edits.leave {
                    pages.navigate(next, push)
                } }, onBack = back, onOpenSession = onOpenSession, sidebar = false)
            }
        }
    }
    SettingsDiscardDialog(edits)
}

@Composable
internal fun SettingsDiscardDialog(edits: SettingsEdits) {
    if (edits.pending == null) return
    NavigationBackHandler(rememberNavigationEventState(NavigationEventInfo.None), isBackEnabled = true) { edits.pending = null }
    Box(Modifier.fillMaxSize().background(color(scrimColor)).clickable { edits.pending = null }, contentAlignment = Alignment.Center) {
        Panel(Modifier.padding(24.dp).widthIn(max = 400.dp).clickable {}.semantics { paneTitle = "Unsaved changes" }, contentPadding = PaddingValues(20.dp)) {
            Text("Discard changes?")
            Spacer(Modifier.height(8.dp))
            Text("Your changes have not been saved.")
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button({ edits.pending = null }) { Text("Keep editing") }
                Button(edits::discard, contentColor = color(destructiveColor)) { Text("Discard") }
            }
        }
    }
}

@Composable
internal fun TrackSettingsEdits(dirty: Boolean) {
    val edits = LocalSettingsEdits.current
    SideEffect { edits?.dirty = dirty }
    DisposableEffect(edits) { onDispose { edits?.dirty = false } }
}

@Composable
internal fun formChanged(value: Any?, saved: Boolean): Boolean {
    var baseline by remember { mutableStateOf(value) }
    LaunchedEffect(saved) { if (saved) baseline = value }
    return value != baseline
}

@Composable
internal fun rememberSettingsViewModel(route: Route.Settings): SettingsViewModel {
    val owner = remember(route) { object : androidx.lifecycle.ViewModelStoreOwner {
        override val viewModelStore = androidx.lifecycle.ViewModelStore()
    } }
    DisposableEffect(owner) { onDispose { owner.viewModelStore.clear() } }
    return koinViewModel(viewModelStoreOwner = owner) { parametersOf(route) }
}

internal class ModalNavigation(val back: (() -> Unit)?)
internal val LocalModalNavigation = staticCompositionLocalOf<ModalNavigation?> { null }

/** A Settings page's title with [back] and [trailing] controls; in a sheet, centered like the platform's sheets. */
@Composable
internal fun SettingsPageHeader(title: String, back: (() -> Unit)? = null, subtitle: String? = null,
    info: String? = null, trailing: @Composable RowScope.() -> Unit = {}) {
    val navigation = LocalModalNavigation.current
    val up = if (navigation != null) navigation.back else back
    if (LocalInSheet.current) Box(Modifier.fillMaxWidth().then(LocalSheetDrag.current).heightIn(min = 52.dp).padding(horizontal = 12.dp, vertical = 4.dp)) {
        if (up != null) Box(Modifier.align(Alignment.CenterStart)) { HeaderButton(Lucide.ArrowLeft, "Back", up) }
        Column(Modifier.align(Alignment.Center).padding(horizontal = 72.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f, fill = false), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, singleLine = true, overflow = TextOverflow.Ellipsis)
                info?.let { InfoButton(it) }
            }
            subtitle?.let { Text(it, fontSize = 12.sp, color = color(mutedColor), singleLine = true, overflow = TextOverflow.Ellipsis) }
        }
        Row(Modifier.align(Alignment.CenterEnd), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp), content = trailing)
    } else {
        TitleBarRow(leading = up != null) {
            if (up != null) HeaderButton(Lucide.ArrowLeft, "Back", up)
            Column(Modifier.weight(1f).padding(start = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, Modifier.weight(1f, fill = false), fontWeight = FontWeight.Normal, singleLine = true, overflow = TextOverflow.Ellipsis)
                    info?.let { InfoButton(it) }
                }
                subtitle?.let { Text(it, fontSize = 12.sp, color = color(mutedColor), singleLine = true) }
            }
            trailing()
        }
        HorizontalSeparator()
    }
}

internal fun SnapshotStateList<Route.Settings>.navigate(route: Route.Settings, push: Boolean) {
    if (!push && isNotEmpty()) this[lastIndex] = route else add(route)
    indexOfFirst { it == route }.takeIf { it >= 0 && it < lastIndex }?.let(::removeAt)
}
