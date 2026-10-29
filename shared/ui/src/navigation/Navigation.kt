package kinuvo.ui.navigation

import kinuvo.app.navigation.*

import kinuvo.protocol.ServerFeature
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.serialization.Serializable
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** Replaces the whole back stack with [route]. */
fun SnapshotStateList<Route>.reset(route: Route) {
    clear()
    add(route)
}

/**
 * Pushes [route]. A Session already in the stack moves to the top instead, keeping its screen state and ViewModel.
 * The oldest entries drop off past [MAX_STACK].
 */
fun SnapshotStateList<Route>.open(route: Route.Chat) {
    val existing = route.sessionId?.let { id -> indexOfFirst { it is Route.Chat && it.agentId == route.agentId && it.sessionId == id } } ?: -1
    add(if (existing >= 0) removeAt(existing) else route)
    while (size > MAX_STACK) removeAt(0)
}

private const val MAX_STACK = 20

/**
 * Shows [route]: pushed as a new screen, or with [push] false in place of the Settings screen on top. An identical
 * earlier entry drops out, so each screen is in the stack once and going back after a save skips its form.
 */
fun SnapshotStateList<Route>.open(route: Route.Settings, push: Boolean) {
    if (!push && lastOrNull() is Route.Settings) set(lastIndex, route) else add(route)
    indexOfFirst { it == route }.takeIf { it < lastIndex }?.let(::removeAt)
    while (size > MAX_STACK) removeAt(0)
}

/** Writes back a [Route.Chat] whose fields were filled in by its screen, keeping its slot. */
fun SnapshotStateList<Route>.update(route: Route.Chat) {
    val i = indexOfFirst { it is Route.Chat && it.slot == route.slot }
    if (i >= 0 && get(i) != route) set(i, route)
}

/**
 * The app's one back stack, which system back (Android), the edge swipe (iOS) and Escape (desktop) pop one level.
 * Browser-style: going back keeps the Route in [ahead] to go forward to, until something new opens. On [compact]
 * layouts Sessions are switched from the drawer, so a Session replaces the one on top instead of stacking up.
 */
@Stable
class History(val backStack: SnapshotStateList<Route>, private val ahead: SnapshotStateList<Route>) {
    var compact by mutableStateOf(false)
    val canGoBack get() = backStack.size > 1
    val canGoForward get() = ahead.isNotEmpty()

    fun back() {
        if (canGoBack) ahead.add(backStack.removeAt(backStack.lastIndex))
    }

    fun forward() {
        ahead.removeLastOrNull()?.let(backStack::add)
    }

    fun open(route: Route.Chat) {
        ahead.clear()
        val replaced = backStack.lastOrNull()?.takeIf { compact && it is Route.Chat }
        backStack.open(route)
        if (replaced != null && backStack.last() != replaced) backStack.remove(replaced)
    }

    fun open(route: Route.Settings, push: Boolean) {
        ahead.clear()
        backStack.open(route, push)
    }

    /** Opens a top-level screen such as the Board, moving it to the top if it's already open. */
    fun open(route: Route) {
        ahead.clear()
        backStack.remove(route)
        backStack.add(route)
        while (backStack.size > MAX_STACK) backStack.removeAt(0)
    }

    /**
     * Goes to a place picked in the shell, e.g. a Session or Home's Board. On [compact] layouts it becomes the only screen,
     * as the shell is where the others are, so the edge swipe reveals the shell instead of going back.
     */
    fun switchTo(route: Route) {
        if (route is Route.Chat) open(route) else open(route)
        if (compact) while (backStack.size > 1) backStack.removeAt(0)
    }

    fun reset(route: Route) {
        ahead.clear()
        backStack.reset(route)
    }
}

val LocalHistory = staticCompositionLocalOf { History(mutableStateListOf(), mutableStateListOf()) }
