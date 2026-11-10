package kinuvo.ui.e2e

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.composables.ui.theme.InteractionMode
import kinuvo.app.connection.Device
import kinuvo.app.connection.DeviceCredentials
import kinuvo.app.connection.Preferences
import kinuvo.app.connection.Servers
import kinuvo.ui.KinuvoApp
import kinuvo.ui.design.LocalBackdropBlur
import kinuvo.ui.design.LocalForcedInteractionMode
import kinuvo.ui.kinuvoApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.koin.compose.KoinIsolatedContext
import org.koin.dsl.module
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.TimeSource

private const val TIMEOUT = 15_000L

/** Address the pairing screen offers, as in Mockstro runs; [MockServer] answers it in-process. */
private const val SERVER_URL = "ws://127.0.0.1:18081/ws"

/**
 * Runs the whole app on a phone-sized touch layout against [profile], like a Maestro flow under Mockstro: fresh in-memory
 * preferences and credentials, onboarding skipped, pairing with the WireMock fixtures. Screenshots go to `build/screens/flows/<flow>/`.
 */
@OptIn(ExperimentalTestApi::class)
internal fun appTest(flow: String, profile: String, block: suspend AppTest.() -> Unit) = runPhoneTest {
    val server = MockServer(profile)
    val values = mutableMapOf<String, String>()
    val tokens = mutableMapOf<String, String>()
    val preferences = object : Preferences {
        override fun get(key: String) = values[key]
        override fun set(key: String, value: String?) { if (value == null) values.remove(key) else values[key] = value }
        override fun clear() = values.clear()
    }
    val credentials = object : DeviceCredentials {
        override fun read(server: String) = tokens[server]
        override fun write(server: String, token: String) { tokens[server] = token }
        override fun delete(server: String) { tokens.remove(server) }
    }
    val koin = kinuvoApplication(module {
        single<Preferences> { preferences }
        single<DeviceCredentials> { credentials }
        single { Device("Test phone", "UTC", SERVER_URL) }
        single { Servers(get(), null, get(), server::connect).apply { onboardingComplete = true } }
    })
    val app = AppTest(this, server, flow)
    mainClock.autoAdvance = false
    try {
        setContent {
            CompositionLocalProvider(
                LocalViewModelStoreOwner provides app.viewModels,
                LocalLifecycleOwner provides app,
                LocalForcedInteractionMode provides InteractionMode.Touch,
                LocalBackdropBlur provides false,
            ) {
                KoinIsolatedContext(koin) { KinuvoApp() }
            }
        }
        app.settle()
        app.block()
    } finally {
        app.viewModels.viewModelStore.clear()
        koin.close()
    }
}

@OptIn(ExperimentalTestApi::class)
internal class AppTest(val ui: ComposeUiTest, val server: MockServer, private val flow: String) : LifecycleOwner {
    override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
    val viewModels = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
    private var shots = 0

    /** Matches what Maestro's text selectors see: a node's text, content description or field text, as a case-insensitive full regex. */
    fun label(text: String): SemanticsMatcher {
        val regex = Regex(text, RegexOption.IGNORE_CASE)
        return SemanticsMatcher("label ~ $text") { node ->
            val config = node.config
            (config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
                config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() +
                listOfNotNull(config.getOrNull(SemanticsProperties.EditableText)?.text)).any { regex.matches(it) }
        }
    }

    private fun shown(matcher: SemanticsMatcher, unmerged: Boolean = false): List<SemanticsNodeInteraction> {
        val all = ui.onAllNodes(matcher, useUnmergedTree = unmerged)
        return List(all.fetchSemanticsNodes().size) { all[it] }.filter { runCatching { it.assertIsDisplayed() }.isSuccess }
    }

    /**
     * Lets the app run: the screen keeps perpetual animations, so the test clock is advanced by hand while Server work
     * runs in real time.
     */
    suspend fun settle(millis: Long = 600) {
        ui.mainClock.advanceTimeBy(millis)
        ui.waitForIdle()
    }

    suspend fun until(description: String, timeout: Long = TIMEOUT, condition: () -> Boolean) {
        val start = TimeSource.Monotonic.markNow()
        while (true) {
            settle(100)
            if (condition()) return
            if (start.elapsedNow().inWholeMilliseconds > timeout) {
                runCatching { ui.saveScreenshot("$flow/failure") }
                throw AssertionError("Timed out waiting for $description; unmatched requests: ${server.unmatched.value}\n" +
                    ui.onRoot().printToString(Int.MAX_VALUE))
            }
            withContext(Dispatchers.Default) { delay(20) }
        }
    }

    fun visible(text: String) = shown(label(text)).isNotEmpty()

    suspend fun await(text: String, timeout: Long = TIMEOUT) = await(label(text), timeout)

    suspend fun await(matcher: SemanticsMatcher, timeout: Long = TIMEOUT): SemanticsNodeInteraction {
        until("${matcher.description} to be shown", timeout) { shown(matcher).isNotEmpty() }
        return shown(matcher).first()
    }

    suspend fun gone(text: String, timeout: Long = TIMEOUT) = until("$text to disappear", timeout) { !visible(text) }

    suspend fun tap(text: String) = tap(label(text))

    /** Lets any screen transition finish first; taps on a screen still sliding in are dropped, as on a device. */
    suspend fun tap(matcher: SemanticsMatcher) { await(matcher); settle(); await(matcher).performClick(); settle() }

    /** Taps the [text] node nearest above [anchor], like Maestro's `above:` selector. */
    suspend fun tapAbove(text: String, anchor: String) = tapNear(text, anchor, above = true)

    /** Taps the [text] node nearest below [anchor], like Maestro's `below:` selector. */
    suspend fun tapBelow(text: String, anchor: String) = tapNear(text, anchor, above = false)

    /** Uses the unmerged tree, since a container merging its descendants would otherwise stand in for [anchor]. */
    private suspend fun tapNear(text: String, anchor: String, above: Boolean) {
        until("$anchor to be shown") { shown(label(anchor), unmerged = true).isNotEmpty() }
        val bounds = shown(label(anchor), unmerged = true).first().fetchSemanticsNode().boundsInRoot
        fun candidates() = shown(label(text), unmerged = true).map { it to it.fetchSemanticsNode().boundsInRoot }
            .filter { (_, it) -> if (above) it.center.y < bounds.center.y else it.center.y > bounds.center.y }
        until("$text ${if (above) "above" else "below"} $anchor") { candidates().isNotEmpty() }
        candidates().minBy { (_, it) -> kotlin.math.abs(it.center.y - bounds.center.y) }.first.performClick()
        settle()
    }

    /** Swipes across the screen between points given in percent, like Maestro's `swipe`. */
    suspend fun swipe(fromX: Int, fromY: Int, toX: Int, toY: Int) {
        ui.onRoot().performTouchInput {
            swipe(Offset(width * fromX / 100f, height * fromY / 100f), Offset(width * toX / 100f, height * toY / 100f), 300)
        }
        settle()
    }

    /** Types into the focused field, like Maestro's `inputText`. */
    suspend fun type(text: String) { ui.onNode(isFocused()).performTextInput(text); settle() }

    suspend fun clear() { ui.onNode(isFocused()).performTextClearance(); settle() }

    /**
     * Brings [text] on screen like Maestro's `scrollUntilVisible`: lazy lists scroll to it directly, other containers are
     * swiped (animated scrolls never finish while the clock is held).
     */
    suspend fun scrollTo(text: String, up: Boolean = false) {
        repeat(12) {
            if (visible(text)) return settle()
            val lazy = ui.onAllNodes(hasScrollToNodeAction())
            val scrolled = List(lazy.fetchSemanticsNodes().size) { lazy[it] }.any { runCatching { it.performScrollToNode(label(text)) }.isSuccess }
            if (!scrolled) swipe(50, if (up) 35 else 70, 50, if (up) 70 else 35) else settle()
        }
        await(text, 1_000)
    }

    suspend fun push(json: String) { server.push(json); settle() }

    /** Asserts the Server got exactly [count] requests matching [stub], waiting briefly for in-flight ones. */
    suspend fun requests(stub: String, count: Int) {
        runCatching { until("$stub x$count", 3_000) { server.hits(stub) == count } }
        assertEquals(count, server.hits(stub), "requests matching $stub; unmatched: ${server.unmatched.value}")
    }

    suspend fun requestsAtLeast(stub: String, count: Int = 1) {
        runCatching { until("$stub x$count+", 3_000) { server.hits(stub) >= count } }
        assertTrue(server.hits(stub) >= count, "requests matching $stub: ${server.hits(stub)} < $count")
    }

    suspend fun background() { ui.runOnUiThread { lifecycle.currentState = Lifecycle.State.CREATED }; settle() }

    suspend fun foreground() { ui.runOnUiThread { lifecycle.currentState = Lifecycle.State.RESUMED }; settle() }

    suspend fun screenshot(name: String) {
        settle()
        ui.saveScreenshot("$flow/${(++shots).toString().padStart(2, '0')}-$name")
    }
}
