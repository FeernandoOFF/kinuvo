package kinuvo.harness.browser

import com.microsoft.playwright.*
import com.microsoft.playwright.options.ScreenshotType
import com.microsoft.playwright.impl.driver.Driver
import kinuvo.harness.core.BrowserService
import kinuvo.harness.core.ScreenSource
import kinuvo.harness.core.ScreenGate
import com.google.gson.JsonObject
import kotlinx.coroutines.flow.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kinuvo.protocol.*
import kotlinx.coroutines.*
import java.io.File
import java.net.URI
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.io.encoding.Base64

/** Playwright calls and event dispatch belong to one thread. Operations are never persisted or replayed. */
class BrowserRuntime(private val home: File) : BrowserService {
    private val dispatcher = Executors.newSingleThreadExecutor { r -> Thread(r, "kinuvo-browser").apply { isDaemon = true } }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var authorizeOwner: suspend (BrowserOwner) -> Unit = {}
    override fun authorize(check: suspend (BrowserOwner) -> Unit) { authorizeOwner = check }
    private val deleted = java.util.concurrent.ConcurrentHashMap.newKeySet<BrowserOwner>()
    private var changed: (BrowserOwner) -> Unit = {}
    override fun onChange(listener: (BrowserOwner) -> Unit) { changed = listener }
    private var playwright: Playwright? = null
    private var closed = false
    private val recordsFile = File(home, "tabs.json")
    @Serializable private data class SavedTab(val id: String, val owner: BrowserOwner, val url: String, val held: Boolean, val control: ScreenControl = ScreenControl())
    private val saved = if (recordsFile.isFile) ProtocolJson.decodeFromString<List<SavedTab>>(recordsFile.readText()).toMutableList() else mutableListOf()
    private val restored = mutableSetOf<BrowserOwner>()
    private var restoring = false
    private val restoreErrors = java.util.concurrent.ConcurrentHashMap<BrowserOwner, String>()
    private val gates = java.util.concurrent.ConcurrentHashMap<ScreenRef, ScreenGate>()
    private val demand = MutableStateFlow(emptyMap<String, Int>())
    private val streams = java.util.concurrent.ConcurrentHashMap<String, SharedFlow<Result<ScreenSample>>>()
    private val streamSlots = kotlinx.coroutines.sync.Semaphore(8)

    private fun persist() {
        if (closed || restoring) return
        val live = tabs.values.filter { !it.page.isClosed }.map {
            SavedTab(it.id, it.owner, it.page.url().substringBefore('?').substringBefore('#').takeIf(::allowed) ?: "about:blank", it.takeover || it.gate.state.value.holder != null, it.gate.state.value)
        }
        saved.removeAll { it.owner in restored && !restoreErrors.containsKey(it.owner) }
        saved.addAll(live.filter { !restoreErrors.containsKey(it.owner) })
        home.mkdirs()
        val temporary = File(home, "tabs.json.tmp")
        temporary.writeText(ProtocolJson.encodeToString(saved))
        Files.move(temporary.toPath(), recordsFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun restore(owner: BrowserOwner) {
        check(owner !in deleted) { "Session was deleted" }
        restoreErrors[owner]?.let { error(it) }
        if (!restored.add(owner)) return
        val records = saved.filter { it.owner == owner }
        restoring = true
        try {
            records.forEach { record ->
                val tab = register(owner, profile(owner.agentId).newPage(), record.id).apply { takeover = record.held; gate = ScreenGate(record.control.let { if (record.held || it.holder != null) it.copy(holder = it.holder ?: "recovery", suspended = true, draining = false) else it }) }
                gates[ScreenRef("browser", tab.id, owner)] = tab.gate
                try { tab.page.navigate(url(record.url)) }
                catch (e: Exception) { throw IllegalStateException("Browser tab restoration failed for ${record.id}", e) }
            }
        } catch (e: Exception) {
            restoreErrors[owner] = "Browser tab restoration failed; restart to retry: ${e.message}"
            throw e
        } finally { restoring = false }
        persist()
    }

    private val liveTabs = mutableSetOf<String>()
    internal suspend fun liveStreams(): Int = withContext(dispatcher) { liveTabs.size }

    override fun screen(owner: BrowserOwner, tabId: String): ScreenSource = object : ScreenSource {
        override val ref = ScreenRef("browser", tabId, owner)
        override val capabilities = ScreenCapabilities(ScreenDelivery.Continuous, setOf("tap", "type", "key", "scroll"), navigation = true, tabs = true)
        override fun frames(thumbnail: Boolean): Flow<ScreenSample> = flow {
            withContext(dispatcher) {
                check(!closed) { "Browser is closed" }
                authorizeOwner(owner)
                restore(owner)
                require(tabs[tabId]?.owner == owner) { "No browser tab for this Agent and Session" }
            }
            if (!thumbnail) demand.update { it + (tabId to ((it[tabId] ?: 0) + 1)) }
            try { emitAll(streams.computeIfAbsent(tabId) {
                live(owner, tabId).map { Result.success(it) }.catch { emit(Result.failure(it)) }.onCompletion { if (it == null) emit(Result.failure(IllegalStateException("Screen closed"))) }.shareIn(scope, SharingStarted.WhileSubscribed(), replay = 0)
            }.map { it.getOrThrow() }) }
            finally { if (!thumbnail) demand.update { it + (tabId to ((it[tabId] ?: 1) - 1).coerceAtLeast(0)) } }
        }
    }

    private fun live(owner: BrowserOwner, tabId: String): Flow<ScreenSample> = channelFlow {
        check(streamSlots.tryAcquire()) { "Too many live Browser streams" }
        var session: CDPSession? = null
        var page: Page? = null
        var quality: Job? = null
        val ended = CompletableDeferred<Unit>()
        val onClose = java.util.function.Consumer<Page> { ended.complete(Unit) }
        try {
            withContext(dispatcher) {
                check(!closed) { "Browser is closed" }
                val tab = requireNotNull(tabs[tabId]) { "No browser tab" }
                require(tab.owner == owner) { "This tab belongs to another Agent or Session" }
                page = tab.page
                tab.page.onClose(onClose)
                val cdp = profile(owner.agentId).newCDPSession(tab.page)
                session = cdp
                cdp.on("Page.screencastFrame") { event ->
                    val ack = JsonObject().apply { addProperty("sessionId", event.get("sessionId").asInt) }
                    // Chromium credit is independent of downstream collection and draw acknowledgement.
                    cdp.send("Page.screencastFrameAck", ack)
                    val metadata = event.getAsJsonObject("metadata")
                    val width = metadata.get("deviceWidth").asInt
                    val height = metadata.get("deviceHeight").asInt
                    val geometry = listOf(width, height, metadata.get("pageScaleFactor"), metadata.get("offsetTop"),
                        metadata.get("scrollOffsetX"), metadata.get("scrollOffsetY")).joinToString(":")
                    val image = event.get("data").asString
                    if (image.length <= 4 * 1024 * 1024) trySend(ScreenSample(image, width, height, geometry))
                }
                quality = scope.launch {
                    try { demand.map { (it[tabId] ?: 0) > 0 }.distinctUntilChanged().collect { selected ->
                        cdp.send("Page.stopScreencast")
                        cdp.send("Page.startScreencast", JsonObject().apply {
                            addProperty("format", "jpeg"); addProperty("quality", if (selected) 65 else 35)
                            addProperty("everyNthFrame", if (selected) 1 else 60)
                        })
                    } } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { ended.completeExceptionally(e) }
                }
                liveTabs.add(tabId)
            }
            ended.await()
        } finally {
            withContext(NonCancellable + dispatcher) {
                quality?.cancelAndJoin()
                page?.let { runCatching { it.offClose(onClose) } }
                session?.let { runCatching { it.send("Page.stopScreencast") }; runCatching { it.detach() } }
                liveTabs.remove(tabId)
                streamSlots.release()
            }
        }
    }.buffer(kotlinx.coroutines.channels.Channel.CONFLATED)

    private val profiles = mutableMapOf<String, BrowserContext>()
    private val tabs = linkedMapOf<String, Tab>()
    private data class Tab(val id: String, val owner: BrowserOwner, val page: Page, var takeover: Boolean = false,
        var observedGeneration: Long = -1, var revision: String = "", var fingerprint: String = "", var refs: Map<String, ElementHandle> = emptyMap(),
        var gate: ScreenGate = ScreenGate(), var thumbnail: String? = null, var thumbnailAt: Long = 0)

    init {
        scope.launch {
            while (isActive) {
                profiles.values.toList().forEach { context -> runCatching { context.pages().firstOrNull()?.waitForTimeout(10.0) } }
                delay(10)
            }
        }
    }

    override suspend fun owners(): List<BrowserOwner> = withContext(dispatcher) { (saved.map { it.owner } + tabs.values.map { it.owner }).distinct() }

    override suspend fun tabs(owner: BrowserOwner): List<BrowserTab> = withContext(dispatcher) {
        check(!closed) { "Browser is closed" }
        authorizeOwner(owner)
        restore(owner)
        tabs.values.toList().filter { it.owner == owner && !it.page.isClosed }.map { info(it, thumbnail = false) }
    }

    private val opening = java.util.concurrent.ConcurrentHashMap<BrowserOwner, ScreenGate>()
    override fun openingGate(owner: BrowserOwner): ScreenGate = opening.computeIfAbsent(owner) { ScreenGate() }

    override suspend fun gate(owner: BrowserOwner, tabId: String): ScreenGate {
        authorizeOwner(owner)
        restoreErrors[owner]?.let { error(it) }
        val ref = ScreenRef("browser", tabId, owner)
        gates[ref]?.let { return it }
        return withContext(dispatcher) {
            restore(owner)
            requireNotNull(tabs[tabId]?.takeIf { it.owner == owner }) { "No browser tab for this Agent and Session" }.gate
        }
    }

    override suspend fun controlChanged(owner: BrowserOwner, tabId: String) = withContext(dispatcher) {
        val tab = requireNotNull(tabs[tabId]?.takeIf { it.owner == owner })
        tab.takeover = tab.gate.state.value.holder != null
        invalidate(tab)
        persist()
        changed(owner)
    }

    override suspend fun command(command: BrowserCommand, user: Boolean): BrowserFrame? {
        check(!user) { "Use screens control and frame-bound input" }
        if (command.action == BrowserAction.Open) return openingGate(command.owner).agent { execute(command, false) }
        val gate = gate(command.owner, requireNotNull(command.tabId))
        return gate.agent { execute(command, false) }
    }

    override suspend fun userCommand(command: BrowserCommand) = execute(command, true)

    private suspend fun execute(command: BrowserCommand, user: Boolean): BrowserFrame? = withContext(dispatcher) {
        check(!closed) { "Browser is closed" }
        authorizeOwner(command.owner)
        restore(command.owner)
        val c = command
        if (c.action == BrowserAction.Open) {
            check(tabs.size < 64) { "Close a browser tab before opening another" }
            val destination = url(c.text ?: "about:blank")
            val page = profile(c.owner.agentId).newPage()
            val tab = register(c.owner, page)
            try { page.navigate(destination); return@withContext capture(tab, semantic = !user) }
            catch (e: Exception) { tabs.remove(tab.id); page.close(); throw IllegalStateException("Browser navigation failed; capture before retrying") }
        }
        val tab = requireNotNull(tabs[c.tabId]) { "No browser tab" }
        require(tab.owner == c.owner) { "This tab belongs to another Agent or Session" }
        check(!tab.page.isClosed) { "This browser tab is closed" }
        if (c.action == BrowserAction.Takeover) {
            check(user) { "Only the user can hand browser control back" }
            tab.takeover = requireNotNull(c.take)
            invalidate(tab)
            return@withContext capture(tab, semantic = false)
        }
        if (c.action == BrowserAction.Capture) {
            check(user || !tab.takeover) { "The user controls this tab; wait for handback before capturing" }
            return@withContext capture(tab, semantic = !user)
        }
        if (user) check(tab.takeover) { "Take over this tab before sending input" }
        else check(!tab.takeover) { "The user controls this tab. Wait for them to hand it back, then capture again." }
        if (!user) check(tab.observedGeneration == tab.gate.state.value.generation) { "Screen control changed; capture again" }
        val page = tab.page
        val element = if (c.action in setOf(BrowserAction.Click, BrowserAction.Type) && !user) {
            require(tab.observedGeneration == tab.gate.state.value.generation && c.revision == tab.revision && tab.revision.isNotEmpty() && fingerprint(page) == tab.fingerprint) { "Stale browser reference; capture again" }
            requireNotNull(tab.refs[c.ref]) { "Unknown browser reference; capture again" }.also {
                check(it.isVisible) { "Stale browser reference; capture again" }
            }
        } else null
        invalidate(tab)
        try {
            when (c.action) {
                BrowserAction.Navigate -> page.navigate(url(requireNotNull(c.text)))
                BrowserAction.Click -> if (user) page.mouse().click(coordinate(c.x, 1280), coordinate(c.y, 800)) else element!!.click()
                BrowserAction.Type -> {
                    require((c.text?.length ?: 0) <= 16_384) { "Text is too long" }
                    if (user) page.keyboard().insertText(c.text.orEmpty()) else element!!.fill(c.text.orEmpty())
                }
                BrowserAction.Key -> { check(user) { "Agent input uses semantic references" }; page.keyboard().press(key(requireNotNull(c.text))) }
                BrowserAction.Scroll -> page.mouse().wheel(0.0, (c.y ?: 500).coerceIn(-2000, 2000).toDouble())
                BrowserAction.Wait -> page.waitForTimeout((c.y ?: 500).coerceIn(0, 5000).toDouble())
                BrowserAction.Close -> { page.close(); tabs.remove(tab.id); return@withContext null }
                else -> error("Unsupported browser action")
            }
        } catch (_: Exception) {
            throw IllegalStateException("Browser action failed or timed out; outcome may be unknown. Capture and inspect before continuing.")
        }
        capture(tab, semantic = !user)
    }.also { if (command.action != BrowserAction.Capture) { withContext(dispatcher) { persist() }; changed(command.owner) } }

    private fun register(owner: BrowserOwner, page: Page, id: String = UUID.randomUUID().toString()): Tab {
        tabs.values.firstOrNull { it.page === page }?.let { return it }
        check(tabs.size < 64) { "Close a browser tab before opening another" }
        page.setDefaultTimeout(10_000.0)
        page.setDefaultNavigationTimeout(30_000.0)
        val tab = Tab(id, owner, page)
        tabs[tab.id] = tab
        gates[ScreenRef("browser", tab.id, owner)] = tab.gate
        page.onPopup { popup ->
            if (tabs.size >= 64) popup.close()
            else {
                register(owner, popup).apply {
                    takeover = tab.takeover
                    gate = ScreenGate(tab.gate.state.value.copy(suspended = tab.takeover, draining = false))
                    gates[ScreenRef("browser", id, owner)] = gate
                }
                persist()
                changed(owner)
            }
        }
        page.onClose { gates.remove(ScreenRef("browser", tab.id, owner)); tab.gate.close(); tabs.remove(tab.id); streams.remove(tab.id); persist(); changed(owner) }
        page.onFrameNavigated { if (it == page.mainFrame()) persist() }
        page.onDialog { it.dismiss() }
        return tab
    }

    private fun profile(agent: String): BrowserContext {
        require(agent.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "Invalid Agent id" }
        return profiles.getOrPut(agent) {
            val pw = runtime()
            val directory = File(home, "profiles/$agent").apply {
                mkdirs(); setReadable(false, false); setReadable(true, true); setWritable(false, false); setWritable(true, true); setExecutable(false, false); setExecutable(true, true)
            }
            pw.chromium().launchPersistentContext(directory.toPath(), BrowserType.LaunchPersistentContextOptions()
                .setHeadless(true).setChromiumSandbox(true).setAcceptDownloads(false).setViewportSize(1280, 800)).also { context ->
                context.pages().toList().forEach { it.close() }
                context.route("**/*") { route ->
                    if (route.request().isNavigationRequest && !allowed(route.request().url())) route.abort() else route.resume()
                }
                context.addInitScript("""(() => {
                    const permitted = value => { try { const u = new URL(value, location.href); return ['http:', 'https:'].includes(u.protocol) || u.href === 'about:blank'; } catch { return false; } };
                    addEventListener('click', event => {
                        const a = event.target.closest?.('a[href]');
                        if (a && !permitted(a.href)) { event.preventDefault(); event.stopImmediatePropagation(); }
                    }, true);
                    addEventListener('submit', event => {
                        if (!permitted(event.target.action)) { event.preventDefault(); event.stopImmediatePropagation(); }
                    }, true);
                    const open = window.open;
                    window.open = function(url, ...rest) { return !url || permitted(url) ? open.call(this, url, ...rest) : null; };
                })()""")
            }
        }
    }

    private fun runtime(): Playwright {
        playwright?.let { return it }
        val binaries = File(home, "runtime/playwright-1.64.0").apply { mkdirs() }
        val env = System.getenv().toMutableMap().apply { put("PLAYWRIGHT_BROWSERS_PATH", binaries.absolutePath) }
        // Invoke the bundled Node installer directly: Java classpaths do not contain nested jars in packaged apps.
        val driver = Driver.ensureDriverInstalled(env, false)
        val marker = File(binaries, ".chromium-installed")
        synchronized(provisionLock) {
            java.io.RandomAccessFile(File(binaries, ".install-lock"), "rw").use { file -> file.channel.lock().use {
                if (!marker.isFile) {
                    val log = File(binaries, "install.log")
                    val process = driver.createProcessBuilder().apply {
                        command().addAll(listOf("install", "chromium"))
                        environment().putAll(env)
                    }.redirectErrorStream(true).redirectOutput(log).start()
                    if (!process.waitFor(300, TimeUnit.SECONDS)) { process.destroyForcibly(); error("Chromium download timed out; retry when the Server has internet access") }
                    check(process.exitValue() == 0) { "Managed Chromium installation failed. See ${log.absolutePath}; verify network access and supported OS dependencies." }
                    marker.writeText("1.64.0")
                }
            } }
        }
        val pw = Playwright.create(Playwright.CreateOptions().setEnv(env))
        if (!File(pw.chromium().executablePath()).isFile) {
            marker.delete(); pw.close(); error("Managed Chromium is missing; retry to provision it again")
        }
        playwright = pw
        return pw
    }

    private fun info(tab: Tab, thumbnail: Boolean = false): BrowserTab {
        if (thumbnail && System.currentTimeMillis() - tab.thumbnailAt > 3000) {
            tab.thumbnail = shot(tab.page, 25)
            tab.thumbnailAt = System.currentTimeMillis()
        }
        return BrowserTab(tab.id, tab.owner.agentId, tab.owner.sessionId, tab.page.title().take(200), tab.page.url().substringBefore('?').substringBefore('#'), tab.takeover, tab.thumbnail)
    }

    private fun capture(tab: Tab, semantic: Boolean): BrowserFrame {
        if (semantic) {
            invalidate(tab)
            tab.refs.values.forEach { runCatching { it.dispose() } }
            val elements = tab.page.querySelectorAll("a,button,input,textarea,select,[role],[contenteditable=true]").let { all ->
                val visible = all.filter { it.isVisible }.take(300)
                all.filter { it !in visible }.forEach { it.dispose() }
                visible
            }
            tab.refs = elements.mapIndexed { i, e -> "e${i + 1}" to e }.toMap()
            tab.fingerprint = fingerprint(tab.page)
            tab.observedGeneration = tab.gate.state.value.generation
            tab.revision = UUID.randomUUID().toString()
        }
        val elements = if (!semantic) emptyList() else tab.refs.map { (ref, e) ->
            BrowserElement(ref, e.getAttribute("role") ?: e.evaluate("e => e.tagName.toLowerCase()").toString(),
                (e.getAttribute("aria-label") ?: e.getAttribute("placeholder") ?: e.evaluate("e => [...(e.labels || [])].map(l => l.innerText).join(' ')").toString().takeIf { it.isNotBlank() } ?: e.innerText()).take(180))
        }
        return BrowserFrame(info(tab), tab.revision, elements, if (semantic) shot(tab.page, 65) else null,
            text = if (semantic) tab.page.evaluate("() => document.body?.innerText || ''").toString().take(24_000) else "")
    }

    private fun shot(page: Page, quality: Int) = Base64.encode(page.screenshot(Page.ScreenshotOptions().setType(ScreenshotType.JPEG).setQuality(quality)))
    private fun fingerprint(page: Page) = page.evaluate("""() => JSON.stringify([location.href, document.body?.innerText,
        [...document.querySelectorAll('a,button,input,textarea,select,[role],[contenteditable=true]')].map(e => {
            const r = e.getBoundingClientRect();
            return [e.tagName, e.getAttribute('role'), e.getAttribute('aria-label'), e.getAttribute('placeholder'),
                e.getAttribute('type'), e.disabled, e.checked, e.value, e.getAttribute('href'), r.x, r.y, r.width, r.height];
        })])""").toString()
    private fun invalidate(tab: Tab) { tab.revision = "" }

    override suspend fun deleteSession(owner: BrowserOwner) = withContext(dispatcher) {
        deleted.add(owner)
        tabs.values.filter { it.owner == owner }.toList().forEach { tab -> tab.page.close(); tabs.remove(tab.id); streams.remove(tab.id) }
        saved.removeAll { it.owner == owner }
        restored.remove(owner)
        restoreErrors.remove(owner)
        persist()
    }

    override suspend fun release(agentId: String) = withContext(dispatcher) {
        tabs.values.filter { it.owner.agentId == agentId }.toList().forEach { tabs.remove(it.id) }
        saved.removeAll { it.owner.agentId == agentId }
        profiles.remove(agentId)?.close()
        persist()
    }

    override suspend fun deleteAgent(agentId: String) {
        release(agentId)
        withContext(dispatcher) {
            val profile = File(home, "profiles/$agentId")
            check(!profile.exists() || profile.deleteRecursively()) { "Browser profile deletion failed" }
        }
    }

    suspend fun close() {
        withContext(dispatcher) { if (!closed) { persist(); closed = true; scope.cancel(); profiles.values.forEach { runCatching { it.close() } }; profiles.clear(); tabs.clear(); streams.clear(); playwright?.close(); playwright = null } }
        dispatcher.close()
    }

    companion object {
        private val provisionLock = Any()
        fun allowed(value: String): Boolean = runCatching {
            if (value == "about:blank") true else URI(value).let { it.scheme?.lowercase() in setOf("http", "https") && !it.host.isNullOrBlank() && it.userInfo == null }
        }.getOrDefault(false)
        private fun url(value: String): String { require(allowed(value)) { "Browser navigation requires http(s) or about:blank, without embedded credentials" }; return value }
        private fun coordinate(value: Int?, limit: Int): Double { require(value != null && value in 0 until limit) { "Input is outside the browser viewport" }; return value.toDouble() }
        private fun key(value: String): String = value.split('+').joinToString("+") {
            when (it.lowercase()) { "enter", "return" -> "Enter"; "escape" -> "Escape"; "delete" -> "Backspace"; "tab" -> "Tab"; "space" -> "Space"; "up" -> "ArrowUp"; "down" -> "ArrowDown"; "left" -> "ArrowLeft"; "right" -> "ArrowRight"; "ctrl" -> "Control"; "cmd" -> "Meta"; "alt", "option" -> "Alt"; "shift" -> "Shift"; else -> it.take(20) }
        }
    }
}
