package kinuvo.harness.server

import kinuvo.harness.core.*
import kinuvo.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.uuid.Uuid

/** One authenticated connection's subscriptions and control claims. */
internal class Screens(
    private val computers: Computers,
    private val browser: () -> BrowserService?,
    private val validate: suspend (BrowserOwner) -> Unit,
    private val scope: CoroutineScope,
    private val device: () -> String,
    private val budget: ScreenBudget,
    private val emit: (ScreenFrame) -> Unit,
    private val status: (ScreenStreamStatus) -> Unit,
) {
    private val lock = Mutex()
    private var closed = false
    private val failures = co.touchlab.stately.collections.ConcurrentMutableMap<ScreenRef, String>()
    private data class Watch(val ref: ScreenRef, val view: ScreenView, val job: Job)
    private val watches = mutableMapOf<String, Watch>()
    private val held = mutableMapOf<ScreenRef, Long>()

    private suspend fun source(ref: ScreenRef): ScreenSource = when (ref.source) {
        "computer" -> { require(ref.owner == null); computers.screen(ref.id) }
        "browser" -> { val owner = requireNotNull(ref.owner); validate(owner); requireNotNull(browser()).screen(owner, ref.id) }
        else -> error("Unknown Screen source")
    }

    private suspend fun gate(ref: ScreenRef): ScreenGate {
        source(ref)
        return if (ref.source == "computer") computers.gate(ref.id) else requireNotNull(browser()).gate(requireNotNull(ref.owner), ref.id)
    }

    suspend fun list(owner: BrowserOwner?): List<ScreenInfo> {
        owner?.let { validate(it) }
        val result = computers.list().map { c ->
            val ref = ScreenRef("computer", c.id)
            ScreenInfo(ref, c.name, source(ref).capabilities, if (c.available) ScreenStatus.Ready else ScreenStatus.Unavailable, gate(ref).state.value)
        }
        val owners = owner?.let { listOf(it) } ?: browser()?.owners().orEmpty()
        return result + owners.flatMap { current ->
            validate(current)
            requireNotNull(browser()).tabs(current).map { tab ->
                val ref = ScreenRef("browser", tab.id, current)
                ScreenInfo(ref, tab.title, source(ref).capabilities, if (failures[ref] == null) ScreenStatus.Ready else ScreenStatus.Failed, gate(ref).state.value)
            }
        }
    }

    suspend fun watch(p: ScreenWatch): ScreenSubscription = lock.withLock {
        check(!closed) { "Screen connection closed" }
        require(watches.size < 4) { "Too many Screen subscriptions" }
        val source = source(p.screen)
        gate(p.screen) // Validate ownership before starting production.
        val id = Uuid.random().toString()
        val epoch = Uuid.random().toString()
        val view = ScreenView(p.screen, id, epoch, 1024 * 1024)
        budget.acquire(device())
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                var last = kotlin.time.TimeSource.Monotonic.markNow() - kotlin.time.Duration.parse("2s")
                source.frames(p.thumbnail).collect { sample ->
                    check(sample.image.length <= 1024 * 1024) { "Screen image exceeds the delivery budget" }
                    failures.remove(p.screen)
                    if (!p.thumbnail || last.elapsedNow().inWholeMilliseconds >= 1000) {
                        last = kotlin.time.TimeSource.Monotonic.markNow()
                        view.offer(sample)
                        view.poll()?.let(emit)
                    }
                }
                view.close()
                status(ScreenStreamStatus(id, ScreenStatus.Unavailable, "Screen stream ended"))
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { view.close(); failures[p.screen] = e.message ?: "Screen failed"; status(ScreenStreamStatus(id, ScreenStatus.Failed, e.message)) }
        }
        watches[id] = Watch(p.screen, view, job)
        job.start()
        ScreenSubscription(id, epoch)
    }

    suspend fun unwatch(id: String) {
        val watch = lock.withLock { watches.remove(id) } ?: return
        try { watch.job.cancelAndJoin(); watch.view.close() }
        finally { budget.release(device()) }
    }

    suspend fun ack(p: ScreenFrameAck) {
        val watch = lock.withLock { requireNotNull(watches[p.subscription]) { "Unknown Screen subscription" } }
        gate(watch.ref)
        watch.view.ack(p)
        watch.view.poll()?.let(emit)
    }

    suspend fun control(holder: String, p: ScreenControlRequest): ScreenControl = lock.withLock {
        check(!closed) { "Screen connection closed" }
        val gate = gate(p.screen)
        val result = if (p.take) gate.acquire(holder, p.generation, steal = p.steal) else gate.release(holder, p.generation)
        if (p.take) held.put(p.screen, result.generation) else held.remove(p.screen)
        if (p.screen.source == "browser") requireNotNull(browser()).controlChanged(requireNotNull(p.screen.owner), p.screen.id)
        else computers.takeover(p.screen.id, p.take)
        result
    }

    suspend fun input(holder: String, p: ScreenInputRequest): ScreenInputResult {
        val watch = lock.withLock { requireNotNull(watches[p.subscription]) { "Unknown Screen subscription" } }
        val ref = watch.ref
        return watch.view.input(holder, p.input, gate(ref)) { action ->
            require(action.kind.action in source(ref).capabilities.inputs) { "Unsupported Screen input" }

            if (ref.source == "computer") computers.input(ref.id, ComputerAction(ComputerActionKind.entries.single { it.action == action.kind.action },
                x = action.x, y = action.y, toX = action.toX, toY = action.toY, text = action.text, key = action.key, direction = action.direction))
            else {
                val kind = when (action.kind) {
                    ScreenActionKind.Tap -> BrowserAction.Click
                    ScreenActionKind.Type -> BrowserAction.Type
                    ScreenActionKind.Key -> BrowserAction.Key
                    ScreenActionKind.Scroll -> BrowserAction.Scroll
                    else -> error("Unsupported Browser input")
                }
                requireNotNull(browser()).userCommand(BrowserCommand(requireNotNull(ref.owner), kind, ref.id,
                    text = if (kind == BrowserAction.Key) action.key else action.text, x = action.x,
                    y = if (kind == BrowserAction.Scroll) if (action.direction == "up") -500 else 500 else action.y))
            }
        }
    }

    suspend fun open(holder: String, p: ScreenOpen): ScreenInfo {
        validate(p.owner)
        val browser = requireNotNull(browser())
        val opening = browser.openingGate(p.owner)
        val claim = opening.acquire(holder, opening.state.value.generation)
        try {
            val frame = opening.input(holder, claim.generation) {
                requireNotNull(browser.userCommand(BrowserCommand(p.owner, BrowserAction.Open, text = p.url)))
            }
            val ref = ScreenRef("browser", frame.tab.id, p.owner)
            control(holder, ScreenControlRequest(ref, 0, true))
            return list(p.owner).single { it.ref == ref }
        } finally { opening.release(holder, claim.generation) }
    }

    suspend fun command(holder: String, p: ScreenCommand) {
        require(p.screen.source == "browser" && p.action in setOf(ScreenCommandKind.Navigate, ScreenCommandKind.Close))
        gate(p.screen).input(holder, p.generation) {
            requireNotNull(browser()).userCommand(BrowserCommand(requireNotNull(p.screen.owner), if (p.action == ScreenCommandKind.Navigate) BrowserAction.Navigate else BrowserAction.Close, p.screen.id, text = p.text))
        }
        Unit
    }

    suspend fun close(holder: String) {
        val (ids, refs) = lock.withLock { closed = true; watches.keys.toList() to held.toList() }
        refs.forEach { (ref, generation) ->
            runCatching {
                gate(ref).disconnect(holder, generation)
                if (ref.source == "browser") requireNotNull(browser()).controlChanged(requireNotNull(ref.owner), ref.id)
            }
        }
        ids.forEach { unwatch(it) }
    }
}
