package kinuvo.server.embedded

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.EditorInfo
import android.webkit.JsResult
import android.webkit.JsPromptResult
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewFeature
import kinuvo.plugins.browser.BrowserDom
import kinuvo.plugins.browser.browserDestination
import kinuvo.plugins.browser.browserNavigationAllowed
import kinuvo.plugins.browser.BrowserObservation
import kinuvo.plugins.browser.browserDeadline
import kinuvo.server.plugin.BrowserService
import kinuvo.server.plugin.ScreenGate
import kinuvo.server.plugin.ScreenSource
import kinuvo.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** All WebView access and tab records belong to Main; ScreenGate arbitrates Agent and user work. */
class AndroidBrowserRuntime(private val context: Context) : BrowserService {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val tabs = linkedMapOf<String, Tab>()
    private val opening = ConcurrentHashMap<BrowserOwner, ScreenGate>()
    private val deletedAgents = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var authorizeOwner: suspend (BrowserOwner) -> Unit = {}
    @Volatile private var changed: (BrowserOwner) -> Unit = {}
    private var closed = false
    val available get() = runCatching { WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE) }.getOrDefault(false)
    override fun authorize(check: suspend (BrowserOwner) -> Unit) { authorizeOwner = check }
    override fun onChange(listener: (BrowserOwner) -> Unit) { changed = listener }
    override fun openingGate(owner: BrowserOwner) = opening.computeIfAbsent(owner) { ScreenGate() }

    private class Tab(val id: String, val owner: BrowserOwner, val surface: AndroidBrowserSurface) {
        val gate = ScreenGate()
        val observation = BrowserObservation()
        val lock = Mutex()
        val frames = MutableSharedFlow<Result<ScreenSample>>(replay = 1, extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        var viewers = 0
        var producer: Job? = null
        var navigation: CompletableDeferred<Unit>? = null
        var geometry = 0L
        var title = "Browser tab"
        var url = "about:blank"
    }
    private suspend fun validate(owner: BrowserOwner) {
        check(!closed && owner.agentId !in deletedAgents) { "Browser or Agent is closed" }
        authorizeOwner(owner)
    }
    private suspend fun tab(owner: BrowserOwner, id: String): Tab {
        validate(owner)
        return requireNotNull(tabs[id]?.takeIf { it.owner == owner }) { "No Browser tab for this Agent" }
    }
    override suspend fun owners(): List<BrowserOwner> = withContext(Dispatchers.Main.immediate) { tabs.values.map { it.owner }.distinct() }
    override suspend fun tabs(owner: BrowserOwner): List<BrowserTab> = withContext(Dispatchers.Main.immediate) {
        validate(owner); tabs.values.filter { it.owner == owner }.map(::info)
    }
    override suspend fun gate(owner: BrowserOwner, tabId: String): ScreenGate = withContext(Dispatchers.Main.immediate) { tab(owner, tabId).gate }
    override suspend fun controlChanged(owner: BrowserOwner, tabId: String) = withContext(Dispatchers.Main.immediate) {
        tab(owner, tabId).observation.invalidate()
        changed(owner)
    }
    override suspend fun command(command: BrowserCommand, user: Boolean): BrowserFrame? {
        check(!user) { "Use Screen control and frame-bound input" }
        val gate = if (command.action == BrowserAction.Open) openingGate(command.owner) else gate(command.owner, requireNotNull(command.tabId))
        return gate.agent { execute(command, false) }
    }
    override suspend fun userCommand(command: BrowserCommand): BrowserFrame? = execute(command, true)

    private suspend fun execute(command: BrowserCommand, user: Boolean): BrowserFrame? = withContext(Dispatchers.Main.immediate) {
        validate(command.owner)
        if (command.action == BrowserAction.Open) {
            check(available) { "This WebView does not support isolated Agent profiles" }
            require(tabs.size < 8) { "Close a Browser tab before opening another (Android limit: 8)" }
            val destination = destination(command.text ?: "about:blank")
            val tab = create(command.owner)
            try { return@withContext tab.lock.withLock { navigate(tab, destination); capture(tab, !user) } }
            catch (e: Throwable) { remove(tab); throw e }
        }
        val tab = tab(command.owner, requireNotNull(command.tabId))
        tab.lock.withLock {
            val control = tab.gate.state.value
            if (user) check(control.holder != null && !control.suspended && !control.draining || command.action == BrowserAction.Close) { "Not performed: take over this tab before sending input" }
            else check(control.holder == null && !control.suspended && !control.draining) { "Not performed: the user controls this tab. Wait for explicit handback." }
            if (command.action == BrowserAction.Capture) return@withLock capture(tab, !user)
            if (!user && command.action != BrowserAction.Close) {
                tab.observation.requireGeneration(control.generation)
                if (command.action in setOf(BrowserAction.Click, BrowserAction.Type)) tab.observation.requireReference(command.revision, control.generation)
            }
            val previousRevision = tab.observation.revision
            tab.observation.invalidate()
            tab.geometry++
            try {
                when (command.action) {
                    BrowserAction.Navigate -> navigate(tab, destination(requireNotNull(command.text)))
                    BrowserAction.Back, BrowserAction.Forward -> {
                        val view = tab.surface.view
                        val back = command.action == BrowserAction.Back
                        if (if (back) view.canGoBack() else view.canGoForward()) {
                            navigation(tab) { if (back) view.goBack() else view.goForward() }
                        }
                    }
                    BrowserAction.Click -> if (user) {
                        val x = requireNotNull(command.x).also { require(it in 0 until AndroidBrowserSurface.WIDTH) }
                        val y = requireNotNull(command.y).also { require(it in 0 until AndroidBrowserSurface.HEIGHT) }
                        val now = SystemClock.uptimeMillis()
                        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                            val event = MotionEvent.obtain(now, SystemClock.uptimeMillis(), action, x.toFloat(), y.toFloat(), 0)
                            try { tab.surface.view.dispatchTouchEvent(event) } finally { event.recycle() }
                        }
                    } else semantic(tab, command, previousRevision)
                    BrowserAction.Type -> {
                        require(command.text.orEmpty().length <= 16_384) { "Not performed: text is too long" }
                        if (user) {
                            val connection = checkNotNull(tab.surface.view.onCreateInputConnection(EditorInfo())) { "Focus a text field first" }
                            check(connection.commitText(command.text.orEmpty(), 1)) { "This field did not accept text" }
                        } else semantic(tab, command, previousRevision)
                    }
                    BrowserAction.Key -> {
                        check(user) { "Agent input uses semantic references" }
                        val parts = requireNotNull(command.text).split('+')
                        val modifiers = parts.dropLast(1).fold(0) { value, modifier -> value or when (modifier) {
                            "Control" -> KeyEvent.META_CTRL_ON; "Meta" -> KeyEvent.META_META_ON
                            "Alt" -> KeyEvent.META_ALT_ON; "Shift" -> KeyEvent.META_SHIFT_ON
                            else -> error("Unsupported Browser modifier")
                        } }
                        val key = when (parts.last()) {
                            "Enter" -> KeyEvent.KEYCODE_ENTER; "Backspace" -> KeyEvent.KEYCODE_DEL
                            "Tab" -> KeyEvent.KEYCODE_TAB; "Escape" -> KeyEvent.KEYCODE_ESCAPE
                            "ArrowUp" -> KeyEvent.KEYCODE_DPAD_UP; "ArrowDown" -> KeyEvent.KEYCODE_DPAD_DOWN
                            "ArrowLeft" -> KeyEvent.KEYCODE_DPAD_LEFT; "ArrowRight" -> KeyEvent.KEYCODE_DPAD_RIGHT
                            "Delete" -> KeyEvent.KEYCODE_FORWARD_DEL; "Home" -> KeyEvent.KEYCODE_MOVE_HOME; "End" -> KeyEvent.KEYCODE_MOVE_END
                            "PageUp" -> KeyEvent.KEYCODE_PAGE_UP; "PageDown" -> KeyEvent.KEYCODE_PAGE_DOWN
                            "A" -> KeyEvent.KEYCODE_A; "C" -> KeyEvent.KEYCODE_C; "X" -> KeyEvent.KEYCODE_X; "Y" -> KeyEvent.KEYCODE_Y; "Z" -> KeyEvent.KEYCODE_Z
                            else -> error("Unsupported Browser key")
                        }
                        val now = SystemClock.uptimeMillis()
                        tab.surface.view.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, key, 0, modifiers))
                        tab.surface.view.dispatchKeyEvent(KeyEvent(now, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, key, 0, modifiers))
                    }
                    BrowserAction.Scroll -> tab.surface.view.scrollBy(0, (command.y ?: 500).coerceIn(-2000, 2000))
                    BrowserAction.Wait -> delay((command.y ?: 500).coerceIn(0, 5000).toLong())
                    BrowserAction.Close -> { remove(tab); return@withLock null }
                    else -> error("Unsupported Browser action")
                }
                capture(tab, !user)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { throw IllegalStateException(BrowserDom.failure(e.message), e) }
            finally { changed(command.owner) }
        }
    }

    private suspend fun semantic(tab: Tab, command: BrowserCommand, revision: String) {
        tab.surface.evaluate(BrowserDom.action(revision, command.ref.orEmpty(), command.text.orEmpty().takeIf { command.action == BrowserAction.Type }))
        if (command.action == BrowserAction.Type) {
            delay(100)
            tab.surface.evaluate(BrowserDom.verify())
        }
    }
    private suspend fun capture(tab: Tab, semantic: Boolean): BrowserFrame {
        val generation = tab.gate.state.value.generation
        val revision = UUID.randomUUID().toString()
        val data = if (semantic) JSONObject(tab.surface.evaluate(BrowserDom.capture(revision))) else null
        val image = if (semantic) tab.surface.freshImage() else null
        if (semantic) tab.observation.capture(revision, generation)
        val elements = data?.getJSONArray("elements")?.let { items -> (0 until items.length()).map { index ->
            val element = items.getJSONObject(index)
            BrowserElement(element.getString("ref"), element.getString("role"), element.getString("label"),
                value = if (element.has("value")) element.getString("value") else null,
                options = element.optJSONArray("options")?.let { options -> (0 until options.length()).map { options.getString(it) } },
                checked = if (element.has("checked")) element.getBoolean("checked") else null)
        } }.orEmpty()
        return BrowserFrame(info(tab), if (semantic) revision else "", elements, image, AndroidBrowserSurface.WIDTH, AndroidBrowserSurface.HEIGHT, data?.getString("text").orEmpty())
    }

    private fun create(owner: BrowserOwner): Tab {
        val surface = AndroidBrowserSurface(context, profile(owner.agentId))
        val tab = Tab(UUID.randomUUID().toString(), owner, surface)
        tabs[tab.id] = tab
        surface.view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = !allowed(request.url.toString())
            override fun onPageStarted(view: WebView, url: String?, icon: Bitmap?) {
                tab.url = url.orEmpty()
                tab.observation.invalidate(); tab.geometry++; changed(owner)
            }
            override fun onPageFinished(view: WebView, url: String?) { if (url == view.url) tab.navigation?.complete(Unit); changed(owner) }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) tab.navigation?.completeExceptionally(IllegalStateException(error.description.toString()))
            }
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                surface.rendererGone()
                tab.observation.invalidate()
                tab.navigation?.completeExceptionally(IllegalStateException("Browser renderer exited"))
                tab.frames.tryEmit(Result.failure(IllegalStateException("Browser renderer exited. Close this tab and open a new one.")))
                changed(owner)
                return true
            }
        }
        surface.view.webChromeClient = object : WebChromeClient() {
            override fun onReceivedTitle(view: WebView, title: String?) { tab.title = title.orEmpty().take(200); changed(owner) }
            override fun onJsAlert(view: WebView, url: String, message: String, result: JsResult): Boolean { result.cancel(); return true }
            override fun onJsConfirm(view: WebView, url: String, message: String, result: JsResult): Boolean { result.cancel(); return true }
            override fun onJsPrompt(view: WebView, url: String, message: String, defaultValue: String?, result: JsPromptResult): Boolean { result.cancel(); return true }
            override fun onJsBeforeUnload(view: WebView, url: String, message: String, result: JsResult): Boolean { result.cancel(); return true }
        }
        changed(owner)
        return tab
    }
    private suspend fun navigate(tab: Tab, url: String) = navigation(tab) { tab.surface.view.loadUrl(url) }
    private suspend fun navigation(tab: Tab, action: () -> Unit) {
        tab.surface.checkReady()
        tab.navigation = CompletableDeferred()
        try { action(); browserDeadline(30_000) { tab.navigation!!.await() } }
        finally { tab.navigation = null }
    }
    private fun info(tab: Tab) = BrowserTab(tab.id, tab.owner.agentId,
        tab.title.ifBlank { "Browser tab" }, tab.url.substringBefore('?').substringBefore('#'),
        tab.gate.state.value.holder != null)

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun screen(owner: BrowserOwner, tabId: String): ScreenSource = object : ScreenSource {
        override val ref = ScreenRef("browser", tabId, owner)
        override val capabilities = ScreenCapabilities(ScreenDelivery.CaptureRefresh, setOf("tap", "type", "key", "scroll"), navigation = true, tabs = true,
            keys = BrowserKeys, modifiers = BrowserModifiers)
        override fun frames(thumbnail: Boolean): Flow<ScreenSample> = flow {
            val current = withContext(Dispatchers.Main.immediate) {
                tab(owner, tabId).also { tab ->
                    tab.viewers++
                    if (tab.producer == null) tab.producer = scope.launch {
                        try {
                            var previousGeometry: String? = null
                            while (isActive) {
                                validate(owner); tab.surface.checkReady()
                                if (tab.lock.isLocked) { delay(100); continue }
                                val before = geometry(tab)
                                val image = if (previousGeometry == before) tab.surface.image() else tab.surface.freshImage()
                                val after = geometry(tab)
                                if (before == after && !tab.lock.isLocked) {
                                    previousGeometry = after
                                    tab.frames.emit(Result.success(ScreenSample(image, AndroidBrowserSurface.WIDTH, AndroidBrowserSurface.HEIGHT, after)))
                                }
                                delay(333)
                            }
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) { tab.frames.emit(Result.failure(e)) }
                    }
                }
            }
            try { emitAll(current.frames.map { it.getOrThrow() }) }
            finally { withContext(NonCancellable + Dispatchers.Main.immediate) {
                current.viewers--
                if (current.viewers == 0) { current.producer?.cancel(); current.producer = null; current.frames.resetReplayCache() }
            } }
        }
    }
    private fun geometry(tab: Tab) = "${tab.geometry}:${tab.surface.view.scrollX}:${tab.surface.view.scrollY}"
    private fun remove(tab: Tab) {
        if (tabs.remove(tab.id) !== tab) return
        tab.gate.close(); tab.observation.invalidate(); tab.producer?.cancel()
        tab.navigation?.completeExceptionally(IllegalStateException("Browser tab closed"))
        tab.frames.tryEmit(Result.failure(IllegalStateException("Browser tab closed")))
        tab.surface.close(); changed(tab.owner)
    }
    override suspend fun release(agentId: String) = withContext(Dispatchers.Main.immediate) {
        tabs.values.filter { it.owner.agentId == agentId }.toList().forEach(::remove)
        opening.entries.filter { it.key.agentId == agentId }.forEach { it.value.close(); opening.remove(it.key) }
    }
    override suspend fun deleteAgent(agentId: String) {
        deletedAgents.add(agentId)
        release(agentId)
        withContext(Dispatchers.Main.immediate) { ProfileStore.getInstance().deleteProfile(profile(agentId)) }
    }
    override suspend fun close() = withContext(Dispatchers.Main.immediate) {
        closed = true
        tabs.values.toList().forEach(::remove)
        opening.values.forEach { it.close() }; opening.clear()
        scope.cancel()
    }
    private fun profile(agent: String): String {
        require(agent.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "Invalid Agent id" }
        return "kinuvo-agent-$agent"
    }
    private fun destination(text: String) = browserDestination(text)
    private fun allowed(value: String) = browserNavigationAllowed(value)
}
