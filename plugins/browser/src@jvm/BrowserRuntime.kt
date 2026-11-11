@file:OptIn(dev.kdriver.cdp.InternalCdpApi::class)

package kinuvo.plugins.browser

import dev.kdriver.cdp.CommandMode
import dev.kdriver.core.browser.Browser
import dev.kdriver.core.browser.Config
import dev.kdriver.core.browser.DefaultBrowser
import dev.kdriver.core.browser.defaultBrowserSearchConfig
import dev.kdriver.core.connection.Connection
import dev.kdriver.core.tab.Tab
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.TimeUnit
import kinuvo.server.plugin.BrowserService
import kinuvo.server.plugin.ScreenGate
import kinuvo.server.plugin.ScreenSource
import kinuvo.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.files.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import kotlinx.coroutines.channels.Channel

private const val BROWSER_START_TIMEOUT_MILLIS = 30_000L
private const val BROWSER_START_ATTEMPTS = 2

class BrowserRuntime(private val home: File) : BrowserService {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var closed = false
    private var authorizeOwner: suspend (BrowserOwner) -> Unit = {}
    private var changed: (BrowserOwner) -> Unit = {}
    private val profiles = mutableMapOf<String, Profile>()
    private val tabs = linkedMapOf<String, Entry>()
    private val opening = mutableMapOf<BrowserOwner, ScreenGate>()
    private val streamSlots = Semaphore(8)
    internal fun liveStreams(): Int = 8 - streamSlots.availablePermits
    private val deleted = mutableSetOf<BrowserOwner>()
    private val restored = mutableSetOf<BrowserOwner>()
    private val records = File(home, "kdriver-tabs.json")
    private val saved = if (records.isFile) ProtocolJson.decodeFromString<List<SavedTab>>(records.readText()).toMutableList() else mutableListOf()
    private val presentationFile = File(home, "kdriver-presentation.json")
    private val presentations = if (presentationFile.isFile) ProtocolJson.decodeFromString<Map<String, BrowserPresentation>>(presentationFile.readText()).toMutableMap() else mutableMapOf()
    private val streams = mutableMapOf<String, SharedFlow<Result<ScreenSample>>>()
    private data class Profile(val browser: Browser, val process: Process, val job: Job, val headless: Boolean)
    private data class Entry(val id: String, val owner: BrowserOwner, val page: Tab, val gate: ScreenGate,
        var revision: String = "", var generation: Long = -1, var events: Job? = null, var width: Int = 1280, var height: Int = 800, val retired: CompletableDeferred<Unit> = CompletableDeferred())
    @Serializable private data class SavedTab(val id: String, val owner: BrowserOwner, val url: String, val control: ScreenControl)

    override fun authorize(check: suspend (BrowserOwner) -> Unit) { authorizeOwner = check }
    override fun onChange(listener: (BrowserOwner) -> Unit) { changed = listener }
    override fun openingGate(owner: BrowserOwner): ScreenGate = synchronized(opening) { opening.getOrPut(owner) { ScreenGate() } }
    private suspend fun Connection.send(method: String, params: JsonObject = buildJsonObject {}) =
        callCommand(method, params, CommandMode.DEFAULT)?.jsonObject ?: buildJsonObject {}
    private suspend fun Tab.evaluate(expression: String): JsonElement {
        val result = send("Runtime.evaluate", buildJsonObject {
            put("expression", expression); put("returnByValue", true); put("userGesture", true)
        })
        result["exceptionDetails"]?.jsonObject?.let { details ->
            error(details["exception"]?.jsonObject?.get("description")?.jsonPrimitive?.content
                ?: details["text"]?.jsonPrimitive?.content ?: "Browser script failed")
        }
        return result["result"]?.jsonObject?.get("value") ?: JsonNull
    }

    private suspend fun profile(agent: String): Profile {
        profiles[agent]?.let { return it }
        require(agent.matches(Regex("[A-Za-z0-9_-]{1,128}")))
        val directory = File(home, "kdriver-profiles/$agent").apply {
            mkdirs(); setReadable(false, false); setReadable(true, true); setWritable(false, false); setWritable(true, true); setExecutable(false, false); setExecutable(true, true)
        }
        val executable = System.getenv("KINUVO_BROWSER_EXECUTABLE")?.let(::File)
            ?: defaultBrowserSearchConfig().findChromeExecutable()?.let { File(it.toString()) }
            ?: error("Set KINUVO_BROWSER_EXECUTABLE to a Chrome or Chromium executable")
        require(executable.canExecute()) { "Browser executable is unavailable" }
        val presentation = presentations[agent] ?: BrowserPresentation()
        var failure: Exception? = null
        repeat(BROWSER_START_ATTEMPTS) {
            try { return start(agent, directory, executable, presentation) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { failure = e }
        }
        throw failure!!
    }

    private suspend fun start(agent: String, directory: File, executable: File, presentation: BrowserPresentation): Profile {
        val log = File(directory, "chrome.log")
        val (process, port) = launchChrome(directory, executable, presentation.headless, log)
        val job = SupervisorJob(scope.coroutineContext[Job])
        val browserScope = CoroutineScope(scope.coroutineContext + job)
        try {
            val browser = DefaultBrowser(browserScope, Config(userDataDir = Path(directory.absolutePath), browserExecutablePath = Path(executable.absolutePath), host = "127.0.0.1", port = port, autoDiscoverTargets = true, debugStringLimit = 0)).also { it.start() }
            browser.connection!!.send("Browser.setDownloadBehavior", buildJsonObject { put("behavior", "deny") })
            val profile = Profile(browser, process, job, presentation.headless)
            profiles[agent] = profile
            browserScope.launch {
                browser.connection!!.events.collect { event ->
                    if (event.method == "Target.targetDestroyed") mutex.withLock {
                        val target = event.params?.jsonObject?.get("targetId")?.jsonPrimitive?.content
                        tabs.values.firstOrNull { it.page.targetId == target }?.let {
                            it.events?.cancel(); it.gate.close(); tabs.remove(it.id); streams.remove(it.id)
                            persist(); changed(it.owner)
                        }
                    }
                    if (event.method == "Target.targetCreated") mutex.withLock {
                        browser.updateTargets()
                        val target = event.params?.jsonObject?.get("targetInfo")?.jsonObject
                        val id = target?.get("targetId")?.jsonPrimitive?.content
                        val page = browser.tabs.firstOrNull { it.targetId == id }
                        if (page != null && tabs.values.none { it.page.targetId == id }) {
                            val parent = tabs.values.firstOrNull { it.page.targetId == page.openerId }
                            if (parent != null) {
                                val control = parent.gate.state.value.let { if (it.holder != null) it.copy(suspended = true, draining = false) else it }
                                register(parent.owner, page, control = control)
                                if (!browserNavigationAllowed(page.url ?: "about:blank")) page.send("Page.close")
                                persist(); changed(parent.owner)
                            }
                        }
                    }
                }
            }
            return profile
        } catch (e: Exception) {
            job.cancel(); process.destroy()
            throw IllegalStateException("${e.message}: ${runCatching { log.readText().takeLast(500) }.getOrDefault("")}", e)
        }
    }

    /** Chrome picks its own debugging port, so no other process can claim it between choosing and binding. Returns once its DevTools endpoint answers. */
    private suspend fun launchChrome(directory: File, executable: File, headless: Boolean, log: File): Pair<Process, Int> {
        val portFile = File(directory, "DevToolsActivePort").apply { delete() }
        val args = mutableListOf(executable.path, "--user-data-dir=${directory.absolutePath}", "--remote-debugging-address=127.0.0.1", "--remote-debugging-port=0", "--no-first-run", "--no-default-browser-check", "--window-size=1280,800", "about:blank")
        if (headless) args.add(1, "--headless=new")
        val process = ProcessBuilder(args).redirectErrorStream(true).redirectOutput(log).start()
        try {
            val port = withTimeoutOrNull(BROWSER_START_TIMEOUT_MILLIS) {
                var port: Int? = null
                while (port == null || !serving(port)) {
                    check(process.isAlive) { "The browser exited while starting" }
                    port = port ?: runCatching { portFile.readLines().first().trim().toInt() }.getOrNull()
                    delay(100)
                }
                port
            } ?: error("The browser did not start in time")
            return process to port
        } catch (e: CancellationException) {
            process.destroy(); throw e
        } catch (e: Exception) {
            process.destroy()
            throw IllegalStateException("${e.message}: ${runCatching { log.readText().takeLast(500) }.getOrDefault("")}", e)
        }
    }

    private suspend fun serving(port: Int) = withContext(Dispatchers.IO) {
        runCatching {
            (URI("http://127.0.0.1:$port/json/version").toURL().openConnection() as HttpURLConnection).run {
                connectTimeout = 2_000; readTimeout = 2_000
                try { responseCode == 200 } finally { disconnect() }
            }
        }.getOrDefault(false)
    }

    private suspend fun register(owner: BrowserOwner, page: Tab, id: String = UUID.randomUUID().toString(), control: ScreenControl = ScreenControl()): Entry {
        tabs.values.firstOrNull { it.page.targetId == page.targetId }?.let { return it }
        check(tabs.size < 64) { "Close a browser tab before opening another" }
        val entry = Entry(id, owner, page, ScreenGate(control))
        tabs[id] = entry
        entry.events = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            page.events.collect { event ->
                val params = event.params?.jsonObject ?: return@collect
                when (event.method) {
                    "Fetch.requestPaused" -> {
                        val destination = params["request"]!!.jsonObject["url"]!!.jsonPrimitive.content
                        val request = params["requestId"]!!
                        if (params["resourceType"]?.jsonPrimitive?.content == "Document" && !browserNavigationAllowed(destination))
                            page.send("Fetch.failRequest", buildJsonObject { put("requestId", request); put("errorReason", "BlockedByClient") })
                        else page.send("Fetch.continueRequest", buildJsonObject { put("requestId", request) })
                    }
                    "Page.javascriptDialogOpening" -> page.send("Page.handleJavaScriptDialog", buildJsonObject { put("accept", false) })
                    "Page.frameNavigated" -> changed(owner)
                }
            }
        }
        page.send("Page.enable")
        page.send("Page.addScriptToEvaluateOnNewDocument", buildJsonObject { put("source", """(() => {
            const permitted = value => { try { const u = new URL(value, location.href); return !u.username && !u.password && (['http:', 'https:'].includes(u.protocol) || u.href === 'about:blank'); } catch { return false; } };
            addEventListener('click', event => {
                const a = event.target.closest?.('a[href]');
                if (a && !permitted(a.href)) { event.preventDefault(); event.stopImmediatePropagation(); }
            }, true);
            addEventListener('submit', event => {
                if (!permitted(event.target.action)) { event.preventDefault(); event.stopImmediatePropagation(); }
            }, true);
            const open = window.open;
            window.open = function(url, ...rest) { return !url || permitted(url) ? open.call(this, url, ...rest) : null; };
        })()""") })
        page.send("Fetch.enable", buildJsonObject { put("patterns", buildJsonArray { add(buildJsonObject { put("urlPattern", "*"); put("resourceType", "Document"); put("requestStage", "Request") }) }) })
        page.send("Emulation.setDeviceMetricsOverride", buildJsonObject { put("width", 1280); put("height", 800); put("deviceScaleFactor", 1); put("mobile", false) })
        if (presentations[owner.agentId]?.hidden == true) window(entry, true)
        return entry
    }

    override suspend fun presentation(owner: BrowserOwner): BrowserPresentation = mutex.withLock {
        authorizeOwner(owner)
        (presentations[owner.agentId] ?: BrowserPresentation()).copy(runningHeadless = profiles[owner.agentId]?.headless)
    }

    override suspend fun presentation(request: BrowserPresentationRequest): BrowserPresentation = mutex.withLock {
        authorizeOwner(request.owner)
        val profile = profiles[request.owner.agentId]
        if (request.hidden != (presentations[request.owner.agentId]?.hidden ?: false))
            tabs.values.filter { it.owner == request.owner }.forEach { window(it, request.hidden) }
        val settings = BrowserPresentation(request.headless, request.hidden)
        presentations[request.owner.agentId] = settings
        check(home.isDirectory || home.mkdirs()) { "Browser settings directory is unavailable" }
        val next = File(home, "kdriver-presentation.json.next")
        next.writeText(ProtocolJson.encodeToString(presentations))
        Files.move(next.toPath(), presentationFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        if (profile != null && profile.headless != request.headless) {
            persist()
            val previous = tabs.values.filter { it.owner == request.owner }
            previous.forEach { tab ->
                tab.retired.complete(Unit)
                tab.events?.cancel(); tab.gate.close()
                streams.remove(tab.id); tabs.remove(tab.id)
            }
            profiles.remove(request.owner.agentId)
            restored.remove(request.owner)
            stop(profile)
            restore(request.owner)
        }
        changed(request.owner)
        settings.copy(runningHeadless = profiles[request.owner.agentId]?.headless)
    }

    private suspend fun window(tab: Entry, hidden: Boolean) {
        val profile = profiles[tab.owner.agentId] ?: return
        if (profile.headless) return
        val connection = requireNotNull(profile.browser.connection)
        val window = connection.send("Browser.getWindowForTarget", buildJsonObject { put("targetId", tab.page.targetId) }).getValue("windowId")
        connection.send("Browser.setWindowBounds", buildJsonObject {
            put("windowId", window); putJsonObject("bounds") { put("windowState", if (hidden) "minimized" else "normal") }
        })
    }

    private suspend fun restore(owner: BrowserOwner) {
        check(!closed) { "Browser is closed" }
        check(owner !in deleted) { "Agent was deleted" }
        authorizeOwner(owner)
        if (owner in restored) return
        val loaded = mutableListOf<Entry>()
        try {
            saved.filter { it.owner == owner }.forEach { record ->
                val page = profile(owner.agentId).browser.get("about:blank", newTab = true)
                val control = record.control.let { if (it.holder != null) it.copy(suspended = true, draining = false) else it }
                loaded += register(owner, page, record.id, control).also { navigate(it, record.url) }
            }
            restored.add(owner)
        } catch (e: Exception) {
            loaded.forEach { runCatching { it.page.send("Page.close") }; tabs.remove(it.id) }
            throw e
        }
    }

    private suspend fun info(entry: Entry): BrowserTab {
        val meta = entry.page.evaluate("JSON.stringify({title:document.title,url:location.href})").jsonPrimitive.content.let { Json.parseToJsonElement(it).jsonObject }
        return BrowserTab(entry.id, entry.owner.agentId, meta["title"]!!.jsonPrimitive.content.take(200), meta["url"]!!.jsonPrimitive.content.substringBefore('?').substringBefore('#'), entry.gate.state.value.holder != null)
    }
    private suspend fun persist() {
        val live = tabs.values.map { SavedTab(it.id, it.owner, info(it).url.takeIf(::browserNavigationAllowed) ?: "about:blank", it.gate.state.value) }
        saved.removeAll { it.owner in restored }
        saved.addAll(live)
        home.mkdirs()
        val temporary = File(home, "kdriver-tabs.json.tmp")
        temporary.writeText(ProtocolJson.encodeToString(saved))
        Files.move(temporary.toPath(), records.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
    private suspend fun entry(owner: BrowserOwner, id: String): Entry {
        restore(owner)
        return requireNotNull(tabs[id]?.takeIf { it.owner == owner }) { "No browser tab for this Agent" }
    }
    override suspend fun owners(): List<BrowserOwner> = mutex.withLock { (saved.map { it.owner } + tabs.values.map { it.owner }).distinct() }
    override suspend fun tabs(owner: BrowserOwner): List<BrowserTab> = mutex.withLock { restore(owner); tabs.values.filter { it.owner == owner }.map { info(it) } }
    override suspend fun gate(owner: BrowserOwner, tabId: String): ScreenGate = mutex.withLock { entry(owner, tabId).gate }
    override suspend fun controlChanged(owner: BrowserOwner, tabId: String) = mutex.withLock { entry(owner, tabId).revision = ""; persist(); changed(owner) }

    override suspend fun command(command: BrowserCommand, user: Boolean): BrowserFrame? {
        check(!user) { "Use screens control and frame-bound input" }
        val gate = if (command.action == BrowserAction.Open) openingGate(command.owner) else gate(command.owner, requireNotNull(command.tabId))
        return gate.agent { execute(command, false) }
    }
    override suspend fun userCommand(command: BrowserCommand): BrowserFrame? = execute(command, true)
    private suspend fun navigate(entry: Entry, value: String) {
        val destination = browserDestination(value)
        val result = entry.page.send("Page.navigate", buildJsonObject { put("url", destination) })
        check(result["errorText"] == null) { "Browser navigation failed" }
        awaitNavigation(entry)
    }
    private suspend fun awaitNavigation(entry: Entry) {
        withTimeout(30_000) {
            delay(200)
            while (entry.page.evaluate("document.readyState").jsonPrimitive.content == "loading") delay(100)
        }
    }
    private suspend fun execute(c: BrowserCommand, user: Boolean): BrowserFrame? = mutex.withLock {
        restore(c.owner)
        if (c.action == BrowserAction.Open) {
            val page = profile(c.owner.agentId).browser.get("about:blank", newTab = true)
            val tab = register(c.owner, page)
            try { navigate(tab, c.text ?: "about:blank"); persist(); changed(c.owner); return@withLock capture(tab, !user) }
            catch (e: Exception) { tabs.remove(tab.id); tab.events?.cancel(); runCatching { page.send("Page.close") }; throw e }
        }
        val tab = entry(c.owner, requireNotNull(c.tabId))
        val held = tab.gate.state.value.holder != null
        check(if (user) held || c.action == BrowserAction.Close else !held) { "Not performed: browser control changed; take over or wait for handback" }
        if (!user && c.action != BrowserAction.Capture) check(tab.generation == tab.gate.state.value.generation) { "Not performed: screen control changed; capture again" }
        if (c.action == BrowserAction.Capture) return@withLock capture(tab, !user)
        val revision = tab.revision
        if (!user && c.action in setOf(BrowserAction.Click, BrowserAction.Type))
            require(revision.isNotEmpty() && c.revision == revision) { "Not performed: stale ref; capture again" }
        tab.revision = ""
        try {
            when (c.action) {
                BrowserAction.Resize -> {
                    check(user) { "Only the controlling Device can resize the Browser" }
                    val width = requireNotNull(c.width).also { require(it in 240..2560) }
                    val height = requireNotNull(c.height).also { require(it in 160..2160) }
                    tab.page.send("Emulation.setDeviceMetricsOverride", buildJsonObject { put("width", width); put("height", height); put("deviceScaleFactor", 1); put("mobile", false) })
                    tab.page.send("Emulation.setVisibleSize", buildJsonObject { put("width", width); put("height", height) })
                    tab.width = width; tab.height = height
                }
                BrowserAction.Navigate -> navigate(tab, requireNotNull(c.text))
                BrowserAction.Back, BrowserAction.Forward -> {
                    val history = tab.page.send("Page.getNavigationHistory")
                    val index = history.getValue("currentIndex").jsonPrimitive.int + if (c.action == BrowserAction.Back) -1 else 1
                    history.getValue("entries").jsonArray.getOrNull(index)?.jsonObject?.let { destination ->
                        require(browserNavigationAllowed(destination.getValue("url").jsonPrimitive.content)) { "Unsupported history destination" }
                        tab.page.send("Page.navigateToHistoryEntry", buildJsonObject { put("entryId", destination.getValue("id")) })
                        awaitNavigation(tab)
                    }
                }
                BrowserAction.Click -> if (user) {
                    require(c.x in 0 until tab.width && c.y in 0 until tab.height) { "Input outside viewport" }
                    tab.page.mouseClick(c.x!!.toDouble(), c.y!!.toDouble())
                } else {
                    val point = Json.parseToJsonElement(tab.page.evaluate(BrowserDom.point(revision, requireNotNull(c.ref))).jsonPrimitive.content).jsonObject
                    tab.page.mouseClick(point.getValue("x").jsonPrimitive.double, point.getValue("y").jsonPrimitive.double)
                }
                BrowserAction.Type -> {
                    require((c.text?.length ?: 0) <= 16_384) { "Not performed: text is too long" }
                    if (user) tab.page.send("Input.insertText", buildJsonObject { put("text", c.text.orEmpty()) })
                    else {
                        val prepared = tab.page.evaluate(BrowserDom.action(revision, requireNotNull(c.ref), c.text.orEmpty(), trusted = true))
                        if (prepared.jsonPrimitive.content == "text") {
                            tab.page.send("Input.insertText", buildJsonObject { put("text", c.text.orEmpty()) })
                            tab.page.evaluate(BrowserDom.commit())
                        }
                        delay(100)
                        tab.page.evaluate(BrowserDom.verify())
                    }
                }
                BrowserAction.Key -> {
                    check(user)
                    val keys = requireNotNull(c.text).split('+')
                    val key = keys.last()
                    val modifiers = keys.dropLast(1).sumOf { when (it) { "Alt" -> 1; "Control" -> 2; "Meta" -> 4; "Shift" -> 8; else -> error("Unsupported modifier") } }
                    val codes = mapOf("Enter" to 13, "Tab" to 9, "Escape" to 27, "Backspace" to 8, "Delete" to 46,
                        "ArrowLeft" to 37, "ArrowUp" to 38, "ArrowRight" to 39, "ArrowDown" to 40, "Home" to 36, "End" to 35,
                        "PageUp" to 33, "PageDown" to 34, "A" to 65, "C" to 67, "X" to 88, "Y" to 89, "Z" to 90)
                    require(key in BrowserKeys && (key.length != 1 || (modifiers and 6) != 0)) { "Unsupported key" }
                    val editing = if ((modifiers and 6) != 0) when (key) {
                        "A" -> "selectAll"; "C" -> "copy"; "X" -> "cut"; "Y" -> "redo"; "Z" -> if ((modifiers and 8) != 0) "redo" else "undo"; else -> null
                    } else null
                    listOf("keyDown", "keyUp").forEach { type -> tab.page.send("Input.dispatchKeyEvent", buildJsonObject {
                        put("type", type); put("key", key); put("windowsVirtualKeyCode", codes.getValue(key)); put("modifiers", modifiers)
                        if (key == "Enter" && type == "keyDown") put("text", "\r")
                        if (editing != null && type == "keyDown") put("commands", buildJsonArray { add(editing) })
                    }) }
                }
                BrowserAction.Scroll -> tab.page.send("Input.dispatchMouseEvent", buildJsonObject { put("type", "mouseWheel"); put("x", tab.width / 2); put("y", tab.height / 2); put("deltaX", 0); put("deltaY", (c.y ?: 500).coerceIn(-2000, 2000)) })
                BrowserAction.Wait -> delay((c.y ?: 500).coerceIn(0, 5000).toLong())
                BrowserAction.Close -> { tab.page.send("Page.close"); tabs.remove(tab.id); tab.events?.cancel(); tab.gate.close(); streams.remove(tab.id); persist(); changed(c.owner); return@withLock null }
                else -> error("Unsupported browser action")
            }
            persist(); changed(c.owner)
            capture(tab, !user)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { throw IllegalStateException(BrowserDom.failure(e.message), e) }
    }

    private suspend fun capture(tab: Entry, semantic: Boolean): BrowserFrame {
        val revision = if (semantic) UUID.randomUUID().toString() else ""
        val snapshot = if (semantic) Json.parseToJsonElement(tab.page.evaluate(BrowserDom.capture(revision)).jsonPrimitive.content).jsonObject else null
        if (semantic) { tab.revision = revision; tab.generation = tab.gate.state.value.generation }
        val image = if (semantic) tab.page.send("Page.captureScreenshot", buildJsonObject { put("format", "jpeg"); put("quality", 65) })["data"]!!.jsonPrimitive.content else null
        return BrowserFrame(info(tab), revision, snapshot?.get("elements")?.let { ProtocolJson.decodeFromJsonElement<List<BrowserElement>>(it) }.orEmpty(), image, width = tab.width, height = tab.height, text = snapshot?.get("text")?.jsonPrimitive?.content.orEmpty())
    }

    override fun screen(owner: BrowserOwner, tabId: String): ScreenSource = object : ScreenSource {
        override val ref = ScreenRef("browser", tabId, owner)
        override val capabilities = ScreenCapabilities(ScreenDelivery.Continuous, setOf("tap", "type", "key", "scroll"), navigation = true, tabs = true, keys = BrowserKeys, modifiers = BrowserModifiers, resize = true, browserWindow = true)
        override fun frames(thumbnail: Boolean): Flow<ScreenSample> = flow {
            val (stream, retired) = mutex.withLock {
                val tab = entry(owner, tabId)
                if (!thumbnail && presentations[owner.agentId]?.hidden != true) tab.page.send("Page.bringToFront")
                streams.getOrPut(tabId) { live(tab).map { Result.success(it) }.catch { emit(Result.failure(it)) }.shareIn(scope, SharingStarted.WhileSubscribed(replayExpirationMillis = 0), replay = 1) } to tab.retired
            }
            emitAll(merge(stream.map { it.getOrThrow() }, flow<ScreenSample> {
                retired.await()
                error("Chrome restarted. Reconnect Live view to continue.")
            }).buffer(Channel.CONFLATED))
        }
    }
    private fun geometry(width: Int, height: Int, scale: Double, top: Double, x: Double, y: Double) =
        listOf(width, height, scale, top, x, y).joinToString(":")

    private fun live(tab: Entry): Flow<ScreenSample> = channelFlow {
        check(streamSlots.tryAcquire()) { "Too many live Browser streams" }
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            tab.page.events.filter { it.method == "Page.screencastFrame" }.collect { event ->
                val params = event.params!!.jsonObject
                tab.page.send("Page.screencastFrameAck", buildJsonObject { put("sessionId", params.getValue("sessionId")) })
                val meta = params.getValue("metadata").jsonObject
                val image = params.getValue("data").jsonPrimitive.content
                if (image.length <= 4 * 1024 * 1024) trySend(ScreenSample(image, meta.getValue("deviceWidth").jsonPrimitive.int, meta.getValue("deviceHeight").jsonPrimitive.int, geometry(meta.getValue("deviceWidth").jsonPrimitive.int, meta.getValue("deviceHeight").jsonPrimitive.int,
                    meta.getValue("pageScaleFactor").jsonPrimitive.double, meta.getValue("offsetTop").jsonPrimitive.double,
                    meta.getValue("scrollOffsetX").jsonPrimitive.double, meta.getValue("scrollOffsetY").jsonPrimitive.double)))
            }
        }
        try {
            mutex.withLock {
                tab.page.send("Emulation.setDeviceMetricsOverride", buildJsonObject { put("width", tab.width); put("height", tab.height); put("deviceScaleFactor", 1); put("mobile", false) })
                tab.page.send("Emulation.setVisibleSize", buildJsonObject { put("width", tab.width); put("height", tab.height) })
                val image = tab.page.send("Page.captureScreenshot", buildJsonObject { put("format", "jpeg"); put("quality", 65) }).getValue("data").jsonPrimitive.content
                check(image.length <= 4 * 1024 * 1024) { "Browser frame exceeds the image budget" }
                val viewport = tab.page.send("Page.getLayoutMetrics").getValue("cssVisualViewport").jsonObject
                trySend(ScreenSample(image, tab.width, tab.height, geometry(tab.width, tab.height,
                    viewport.getValue("scale").jsonPrimitive.double, 0.0,
                    viewport.getValue("pageX").jsonPrimitive.double, viewport.getValue("pageY").jsonPrimitive.double)))
                tab.page.send("Page.startScreencast", buildJsonObject { put("format", "jpeg"); put("quality", 65); put("everyNthFrame", 1) })
            }
            tab.retired.await()
            error("Chrome restarted. Reconnect Live view to continue.")
        } finally {
            withContext(NonCancellable) {
                collector.cancel()
                try { withTimeoutOrNull(2000) { collector.join(); runCatching { tab.page.send("Page.stopScreencast") } } }
                finally { streamSlots.release() }
            }
        }
    }.buffer(Channel.CONFLATED)

    private suspend fun stop(profile: Profile) {
        runCatching { profile.browser.stop() }
        withContext(Dispatchers.IO) {
            if (!profile.process.waitFor(5, TimeUnit.SECONDS)) {
                profile.process.destroy()
                if (!profile.process.waitFor(5, TimeUnit.SECONDS)) profile.process.destroyForcibly()
            }
        }
        profile.job.cancel()
    }

    override suspend fun release(agentId: String) = mutex.withLock {
        tabs.values.filter { it.owner.agentId == agentId }.toList().forEach { it.events?.cancel(); it.gate.close(); streams.remove(it.id); tabs.remove(it.id) }
        profiles.remove(agentId)?.let { stop(it) }
        saved.removeAll { it.owner.agentId == agentId }; restored.remove(BrowserOwner(agentId)); persist()
    }
    override suspend fun deleteAgent(agentId: String) {
        mutex.withLock { deleted.add(BrowserOwner(agentId)) }
        release(agentId)
        check(File(home, "kdriver-profiles/$agentId").let { !it.exists() || it.deleteRecursively() }) { "Browser profile deletion failed" }
    }
    override suspend fun close() = mutex.withLock {
        if (!closed) {
            runCatching { persist() }; closed = true
            profiles.values.forEach { stop(it) }
            tabs.values.forEach { it.events?.cancel(); it.gate.close() }
            profiles.clear(); tabs.clear(); scope.cancel()
        }
    }
}
