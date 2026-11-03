package kinuvo.app.screen

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kinuvo.app.connection.ConnectedServer
import kinuvo.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class LiveViewState(
    val screens: List<ScreenInfo> = emptyList(), val selected: ScreenRef? = null,
    val connected: Boolean = false, val loaded: Boolean = false, val device: String? = null,
    val subscription: ScreenSubscription? = null, val frame: ScreenFrame? = null,
    val frameReceivedAt: String? = null, val displayed: ScreenFrame? = null, val pending: Boolean = false, val resizing: Boolean = false, val error: String? = null,
    val thumbnails: Map<ScreenRef, ScreenFrame> = emptyMap(),
    val presentation: BrowserPresentation? = null,
    val requestReason: String? = null, val owner: BrowserOwner? = null,
) {
    val screen get() = screens.firstOrNull { it.ref == selected }
    val control get() = screen?.control ?: ScreenControl()
    val ownsControl get() = device != null && control.holder == device && !control.suspended && !control.draining && !control.closed
    val interactive get() = connected && ownsControl && !pending && displayed != null && screen?.status == ScreenStatus.Ready
    fun accepts(frame: ScreenFrame) = connected && frame.screen == selected && frame.subscription == subscription?.id && frame.epoch == subscription.epoch
}

class LiveViewModel(private val repository: ScreensRepository, private val owner: BrowserOwner? = null,
    selected: ScreenRef? = null, reason: String? = null) : ViewModel() {
    constructor(connection: ConnectedServer, owner: BrowserOwner?, selected: ScreenRef?, reason: String?) : this(ConnectedScreens(connection), owner, selected, reason) {
        connection.bind(viewModelScope)
        cleanup = { id -> connection.scope.launch { runCatching { repository.unwatch(id) } } }
    }
    val state: StateFlow<LiveViewState>
        field = MutableStateFlow(LiveViewState(selected = selected, requestReason = reason, owner = owner))
    private val lock = Mutex()
    private var revision = 0L
    private var sequence = 0L
    private var early: ScreenFrame? = null
    private var starting = false
    private var stopped = false
    private var visible = true
    private var earlyStatus: ScreenStreamStatus? = null
    private var overview = emptyList<ScreenRef>()
    private var awaitingPreview: ScreenRef? = null
    private var earlyPreview: ScreenFrame? = null
    private val previews = mutableMapOf<ScreenRef, ScreenSubscription>()
    private val previewAcks = mutableSetOf<ScreenFrameAck>()
    private var cleanup: ((String) -> Unit)? = null

    init {
        viewModelScope.launch { repository.frames.collect { frame ->
            if (state.value.accepts(frame)) state.update { it.copy(frame = frame, frameReceivedAt = kotlin.time.Clock.System.now().toString()) }
            else if (previews[frame.screen]?.let { it.id == frame.subscription && it.epoch == frame.epoch } == true)
                state.update { it.copy(thumbnails = it.thumbnails + (frame.screen to frame)) }
            else if (frame.screen == awaitingPreview) earlyPreview = frame
            else if (starting && frame.screen == state.value.selected) early = frame
        } }
        viewModelScope.launch { repository.status.collect { status ->
            if (status.subscription == state.value.subscription?.id) streamStatus(status)
            else if (starting) earlyStatus = status
        } }
        viewModelScope.launch { repository.changes.collect { refresh() } }
        viewModelScope.launch { repository.connected.collect { connected ->
            revision++
            early = null
            previews.clear(); previewAcks.clear()
            state.update { it.copy(connected = connected, subscription = null, frame = if (connected) null else it.frame, displayed = null, device = null, pending = false, resizing = false, thumbnails = emptyMap()) }
            if (connected) refresh()
        } }
    }

    private fun streamStatus(status: ScreenStreamStatus) {
        state.update { s -> s.copy(displayed = null, resizing = false, error = status.message,
            screens = s.screens.map { if (it.ref == s.selected) it.copy(status = status.status) else it }) }
    }
    private suspend fun watch(ref: ScreenRef, thumbnail: Boolean, version: Long): ScreenSubscription? = withContext(NonCancellable) {
        val sub = repository.watch(ref, thumbnail)
        if (stopped || !visible || version != revision || !state.value.connected) { repository.unwatch(sub.id); null } else sub
    }
    fun visible(show: Boolean) {
        if (visible == show) return
        visible = show
        revision++
        state.update { it.copy(displayed = null, pending = false, resizing = false) }
        viewModelScope.launch { attempt { lock.withLock { if (show && state.value.connected) reconcile(); restart() } } }
    }
    fun retry() { viewModelScope.launch { attempt { lock.withLock { reconcile(); restart() } } } }
    fun select(ref: ScreenRef) {
        if (state.value.screens.none { it.ref == ref }) return
        if (owner != null && ref.owner != null && ref.owner != owner) return
        revision++
        state.update { it.copy(selected = ref, frame = null, displayed = null, error = null, pending = false, resizing = false) }
        viewModelScope.launch { attempt { lock.withLock { restart() } } }
    }
    /** Shows [ref] once listed, e.g. when a chat card asks for it while the panel is already open. */
    fun focus(ref: ScreenRef?, reason: String?) {
        state.update { it.copy(requestReason = reason) }
        ref?.let(::select)
    }
    private suspend fun reconcile() {
        val device = repository.device()
        val all = repository.list(owner).filter { it.ref.owner == owner }
        val presentation = owner?.takeIf { all.any { it.capabilities.browserWindow } }?.let { repository.presentation(it) }
        state.update { s ->
            val selected = s.selected?.takeIf { ref -> all.any { it.ref == ref } } ?: all.firstOrNull { owner != null && it.ref.owner == owner }?.ref ?: all.firstOrNull()?.ref
            s.copy(screens = all, selected = selected, device = device, loaded = true, presentation = presentation,
                displayed = s.displayed.takeIf { selected == s.selected && all.firstOrNull { it.ref == selected }?.control == s.control })
        }
    }
    private suspend fun refresh() = attempt { lock.withLock {
        if (!state.value.connected || !visible) return@withLock
        val old = state.value.selected
        reconcile()
        if (old != state.value.selected || state.value.subscription == null) restart()
    } }
    private suspend fun restart() {
        previews.values.toList().forEach { repository.unwatch(it.id) }
        previews.clear(); previewAcks.clear()
        state.update { it.copy(thumbnails = emptyMap()) }
        val old = state.value.subscription
        state.update { it.copy(subscription = null, displayed = null, frame = null) }
        old?.let { repository.unwatch(it.id) }
        if (!visible || !state.value.connected) return
        val ref = state.value.selected ?: return
        val version = revision
        starting = true
        early = null; earlyStatus = null
        try {
            val sub = watch(ref, false, version) ?: return
            sequence = 0
            state.update { it.copy(subscription = sub) }
            earlyStatus?.takeIf { it.subscription == sub.id }?.let(::streamStatus)
            early?.takeIf { state.value.accepts(it) }?.let { frame -> state.update { it.copy(frame = frame, frameReceivedAt = kotlin.time.Clock.System.now().toString()) } }
        } finally { starting = false; early = null }
        preview()
    }
    fun showOverview(refs: List<ScreenRef>) {
        if (overview == refs) return
        overview = refs
        viewModelScope.launch { attempt { lock.withLock { preview() } } }
    }
    private suspend fun preview() {
        val wanted = if (visible && state.value.connected) overview.filter { it != state.value.selected && state.value.screens.any { s -> s.ref == it } }.take(3).toSet() else emptySet()
        previews.keys.toList().filter { it !in wanted }.forEach { ref -> previews.remove(ref)?.let { repository.unwatch(it.id) } }
        wanted.filter { it !in previews }.forEach { ref ->
            val version = revision
            awaitingPreview = ref; earlyPreview = null
            try {
                val sub = watch(ref, true, version) ?: return@forEach
                previews[ref] = sub
                earlyPreview?.takeIf { it.subscription == sub.id && it.epoch == sub.epoch }?.let { frame ->
                    state.update { it.copy(thumbnails = it.thumbnails + (ref to frame)) }
                }
            } finally { awaitingPreview = null; earlyPreview = null }
        }
    }
    fun drawn(frame: ScreenFrame) {
        val preview = previews[frame.screen]
        if (preview?.id == frame.subscription && preview.epoch == frame.epoch) {
            if (previewAcks.add(frame.ack())) viewModelScope.launch {
                lock.withLock { if (previews[frame.screen] == preview) attempt { repository.ack(frame.ack()) } }
                previewAcks.remove(frame.ack())
            }
            return
        }

        if (!state.value.accepts(frame) || state.value.displayed?.sequence == frame.sequence) return
        viewModelScope.launch { attempt { lock.withLock {
            if (!state.value.accepts(frame) || state.value.displayed?.ack() == frame.ack()) return@withLock
            repository.ack(frame.ack())
            if (state.value.accepts(frame)) state.update { it.copy(displayed = frame, resizing = false) }
        } } }
    }
    fun presentation(headless: Boolean, hidden: Boolean) = mutate {
        val browser = owner ?: return@mutate
        val updated = repository.presentation(BrowserPresentationRequest(browser, headless, hidden))
        state.update { it.copy(presentation = updated) }
    }
    fun decodeFailed() { state.update { it.copy(displayed = null, error = "Could not decode this frame. Refresh Live view.") } }
    fun takeover(take: Boolean, steal: Boolean = false) = mutate {
        val s = state.value
        val ref = s.selected ?: return@mutate
        repository.control(ScreenControlRequest(ref, s.control.generation, take, steal))
    }
    fun open(url: String) = mutate {
        val scope = owner ?: return@mutate
        val opened = repository.open(ScreenOpen(scope, url.ifBlank { "about:blank" }))
        state.update { it.copy(selected = opened.ref) }
    }
    fun command(action: ScreenCommandKind, text: String? = null) {
        if (!state.value.interactive) return
        mutate { val s = state.value; repository.command(ScreenCommand(requireNotNull(s.selected), s.control.generation, action, text)) }
    }
    private var viewport: ScreenCommand? = null
    fun resize(width: Int, height: Int) {
        val s = state.value
        if (!s.connected || s.pending || !s.ownsControl || s.screen?.capabilities?.resize != true) return
        val w = width.coerceIn(240, 2560)
        val h = height.coerceIn(160, 2160)
        if (s.frame?.sample?.let { it.width == w && it.height == h } == true) return
        val request = ScreenCommand(requireNotNull(s.selected), s.control.generation, ScreenCommandKind.Resize, width = w, height = h)
        if (viewport == request) return
        viewport = request
        mutate(resizing = true) { repository.command(request) }
    }
    fun close(ref: ScreenRef) {
        val screen = state.value.screens.firstOrNull { it.ref == ref }?.takeIf { it.capabilities.tabs } ?: return
        mutate { repository.command(ScreenCommand(ref, screen.control.generation, ScreenCommandKind.Close, null)) }
    }
    private data class QueuedInput(val frame: ScreenFrame, val generation: Long, val revision: Long, val actions: List<ScreenAction>)
    private val inputs = ArrayDeque<QueuedInput>()
    private var inputJob: Job? = null

    fun input(frame: ScreenFrame, actions: List<ScreenAction>) {
        val s = state.value
        if (!s.interactive || s.displayed != frame || actions.isEmpty() || actions.size > 32 || actions.any { it.kind.action !in s.screen!!.capabilities.inputs }) return
        if (inputs.size >= 64) {
            inputs.clear()
            state.update { it.copy(error = "Input is arriving too quickly. Observe the page before continuing.", displayed = null) }
            return
        }
        inputs.addLast(QueuedInput(frame, s.control.generation, revision, actions.toList()))
        if (inputJob?.isActive == true) return
        inputJob = viewModelScope.launch {
            while (inputs.isNotEmpty()) {
                val queued = inputs.removeFirst()
                lock.withLock {
                    val current = state.value
                    val displayed = current.displayed
                    if (queued.revision != revision || !current.interactive || displayed == null ||
                        current.control.generation != queued.generation || !current.accepts(queued.frame) ||
                        displayed.sample.geometry != queued.frame.sample.geometry ||
                        (queued.actions.any { it.kind != ScreenActionKind.Key && it.kind != ScreenActionKind.Type } && displayed.ack() != queued.frame.ack())) {
                        inputs.clear()
                        return@withLock
                    }
                    try {
                        val request = ScreenInputRequest(displayed.subscription, ScreenInput(displayed.ack(), displayed.sample.geometry,
                            queued.generation, sequence++, queued.actions))
                        val result = repository.input(request)
                        check(result.applied == queued.actions.size && result.message == null) {
                            "${result.applied}/${queued.actions.size} inputs applied. ${result.message ?: "Observe before trying again."}"
                        }
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) {
                        inputs.clear()
                        if (queued.revision == revision && state.value.control.generation == queued.generation) {
                            state.update { it.copy(error = "Outcome unconfirmed. Observe before trying again. ${e.message.orEmpty()}", displayed = null) }
                            if (state.value.connected) attempt { reconcile(); restart() }
                        }
                    }
                }
            }
        }
    }
    private fun mutate(rewatch: Boolean = true, resizing: Boolean = false, block: suspend () -> Unit) {
        if (!state.value.connected || state.value.pending) return
        val version = revision
        val before = state.value
        state.update { it.copy(pending = true, resizing = resizing, displayed = null, error = null) }
        viewModelScope.launch {
            lock.withLock {
                var completed = false
                try { if (version == revision) { block(); completed = true } }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { state.update { it.copy(error = "Outcome unconfirmed. Observe before trying again. ${e.message.orEmpty()}", resizing = false) } }
                finally {
                    if (version == revision) {
                        attempt {
                            reconcile()
                            if (rewatch || !completed || state.value.control != before.control) restart()
                            else state.update { it.copy(displayed = before.displayed) }
                        }
                        state.update { it.copy(pending = false) }
                    }
                }
            }
        }
    }
    private suspend fun attempt(block: suspend () -> Unit) {
        try { block() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { state.update { it.copy(error = e.message ?: "Live view unavailable", displayed = null) } }
    }
    override fun onCleared() {
        stopped = true
        (previews.values.map { it.id } + listOfNotNull(state.value.subscription?.id)).forEach { cleanup?.invoke(it) }
    }
}

fun ScreenFrame.ack() = ScreenFrameAck(subscription, epoch, sequence)

