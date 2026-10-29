package kinuvo.harness.browser

import com.microsoft.playwright.*
import com.microsoft.playwright.options.ScreenshotType
import com.microsoft.playwright.impl.driver.Driver
import kinuvo.harness.core.BrowserService
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
    private var changed: (BrowserOwner) -> Unit = {}
    override fun onChange(listener: (BrowserOwner) -> Unit) { changed = listener }
    private var playwright: Playwright? = null
    private var closed = false
    private val profiles = mutableMapOf<String, BrowserContext>()
    private val tabs = linkedMapOf<String, Tab>()
    private data class Tab(val id: String, val owner: BrowserOwner, val page: Page, var takeover: Boolean = false,
        var revision: String = "", var fingerprint: String = "", var refs: Map<String, ElementHandle> = emptyMap(),
        var thumbnail: String? = null, var thumbnailAt: Long = 0)

    init {
        scope.launch {
            while (isActive) {
                profiles.values.toList().forEach { context -> runCatching { context.pages().firstOrNull()?.waitForTimeout(10.0) } }
                delay(200)
            }
        }
    }

    override suspend fun tabs(owner: BrowserOwner): List<BrowserTab> = withContext(dispatcher) {
        check(!closed) { "Browser is closed" }
        tabs.values.toList().filter { it.owner == owner && !it.page.isClosed }.map { info(it, thumbnail = true) }
    }

    override suspend fun command(command: BrowserCommand, user: Boolean): BrowserFrame? = withContext(dispatcher) {
        check(!closed) { "Browser is closed" }
        val c = command
        if (c.action == BrowserAction.Open) {
            check(tabs.size < 64) { "Close a browser tab before opening another" }
            val destination = url(c.text ?: "about:blank")
            val page = profile(c.owner.agentId).newPage()
            val tab = register(c.owner, page).apply { takeover = user }
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
        val page = tab.page
        val element = if (c.action in setOf(BrowserAction.Click, BrowserAction.Type) && !user) {
            require(c.revision == tab.revision && tab.revision.isNotEmpty() && fingerprint(page) == tab.fingerprint) { "Stale browser reference; capture again" }
            requireNotNull(tab.refs[c.ref]) { "Unknown browser reference; capture again" }.also {
                check(it.isVisible) { "Stale browser reference; capture again" }
                check(it.getAttribute("type")?.lowercase() != "password") { "Ask the user to take over for sign-in" }
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
    }.also { if (command.action != BrowserAction.Capture) changed(command.owner) }

    private fun register(owner: BrowserOwner, page: Page): Tab {
        tabs.values.firstOrNull { it.page === page }?.let { return it }
        check(tabs.size < 64) { "Close a browser tab before opening another" }
        page.setDefaultTimeout(10_000.0)
        page.setDefaultNavigationTimeout(30_000.0)
        val tab = Tab(UUID.randomUUID().toString(), owner, page)
        tabs[tab.id] = tab
        page.onPopup { popup ->
            if (tabs.size >= 64) popup.close()
            else { register(owner, popup).takeover = tab.takeover; changed(owner) }
        }
        page.onClose { tabs.remove(tab.id); changed(owner) }
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
            tab.revision = UUID.randomUUID().toString()
        }
        val elements = if (!semantic) emptyList() else tab.refs.map { (ref, e) ->
            val password = e.getAttribute("type")?.lowercase() == "password"
            BrowserElement(ref, e.getAttribute("role") ?: e.evaluate("e => e.tagName.toLowerCase()").toString(),
                if (password) "Password (user Takeover required)" else (e.getAttribute("aria-label") ?: e.getAttribute("placeholder") ?: e.evaluate("e => [...(e.labels || [])].map(l => l.innerText).join(' ')").toString().takeIf { it.isNotBlank() } ?: e.innerText()).take(180))
        }
        return BrowserFrame(info(tab), tab.revision, elements, shot(tab.page, 65),
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

    suspend fun release(agent: String) = withContext(dispatcher) {
        tabs.values.filter { it.owner.agentId == agent }.toList().forEach { tabs.remove(it.id) }
        profiles.remove(agent)?.close()
    }

    suspend fun close() {
        withContext(dispatcher) { if (!closed) { closed = true; scope.cancel(); profiles.values.forEach { runCatching { it.close() } }; profiles.clear(); tabs.clear(); playwright?.close(); playwright = null } }
        dispatcher.close()
    }

    companion object {
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
