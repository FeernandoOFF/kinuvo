package kinuvo.app

import kinuvo.app.screen.*
import kinuvo.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import androidx.lifecycle.ViewModelStore
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class LiveViewModelTest {

    private val ref = ScreenRef("computer", "one")
    private class Fake(val ref: ScreenRef) : ScreensRepository {
        override val connected = MutableStateFlow(false)
        override val frames = MutableSharedFlow<ScreenFrame>(extraBufferCapacity = 5)
        override val status = MutableSharedFlow<ScreenStreamStatus>()
        override val changes = MutableSharedFlow<BrowserOwner?>()
        var control = ScreenControl(1, "device")
        var epoch = 0
        var watches = mutableListOf<String>()
        var inputs = mutableListOf<ScreenInputRequest>()
        var partial = false
        var emitWhileStarting = false
        override suspend fun device() = "device"
        var screens: List<ScreenInfo>? = null
        override suspend fun list(owner: BrowserOwner?) = screens ?: listOf(ScreenInfo(ref, "Phone", ScreenCapabilities(ScreenDelivery.CaptureRefresh, setOf("tap")), ScreenStatus.Ready, control))
        override suspend fun watch(ref: ScreenRef, thumbnail: Boolean): ScreenSubscription {
            val sub = ScreenSubscription("s${++epoch}", "e$epoch")
            watches.add(sub.id)
            if (emitWhileStarting) { frames.emit(frame()); yield() }
            return sub
        }
        override suspend fun unwatch(id: String) { watches.remove(id) }
        override suspend fun ack(frame: ScreenFrameAck) {}
        override suspend fun control(request: ScreenControlRequest) = control
        override suspend fun input(request: ScreenInputRequest): ScreenInputResult { inputs.add(request); return ScreenInputResult(if (partial) 0 else request.input.actions.size, if (partial) "Unknown outcome" else null) }
        override suspend fun open(request: ScreenOpen): ScreenInfo = error("unused")
        override suspend fun command(request: ScreenCommand) {}
        fun frame() = ScreenFrame(ref, "s$epoch", "e$epoch", 1, ScreenSample("image", 100, 100, "geometry"))
    }
    private fun run(block: suspend TestScope.(Fake, LiveViewModel) -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val fake = Fake(ref)
        val vm = LiveViewModel(fake)
        val store = ViewModelStore().also { it.put("live", vm) }
        try { runCurrent(); fake.connected.value = true; runCurrent(); block(fake, vm) }
        finally { store.clear(); Dispatchers.resetMain() }
    }
    @Test fun computersExcludeBrowsersAndAgentsStayIsolated() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = BrowserOwner("agent")
        val other = BrowserOwner("other-agent")
        val first = ScreenRef("browser", "first", owner)
        val second = ScreenRef("browser", "second", other)
        val fake = Fake(first)
        fake.screens = listOf(first, second).map { ScreenInfo(it, it.id, ScreenCapabilities(ScreenDelivery.Continuous, emptySet()), ScreenStatus.Ready) }
        val global = LiveViewModel(fake)
        val scoped = LiveViewModel(fake, owner)
        val store = ViewModelStore().also { it.put("global", global); it.put("scoped", scoped) }
        try {
            runCurrent(); fake.connected.value = true; runCurrent()
            assertEquals(emptyList(), global.state.value.screens.map { it.ref })
            global.select(second); runCurrent(); assertEquals(null, global.state.value.selected)
            assertEquals(listOf(first), scoped.state.value.screens.map { it.ref })
            scoped.select(second); runCurrent(); assertEquals(first, scoped.state.value.selected)
        } finally { store.clear(); Dispatchers.resetMain() }
    }
    @Test fun onlyDrawnEpochCanSendAndDisconnectInvalidatesImmediately() = run { fake, vm ->
        val frame = fake.frame()
        fake.frames.emit(frame); runCurrent()
        assertFalse(vm.state.value.interactive)
        vm.drawn(frame); runCurrent()
        assertTrue(vm.state.value.interactive)
        fake.connected.value = false; runCurrent()
        assertFalse(vm.state.value.interactive)
        fake.connected.value = true; runCurrent()
        fake.frames.emit(frame); vm.drawn(frame); runCurrent()
        assertNull(vm.state.value.displayed)
        assertNull(vm.state.value.frame)
        val next = fake.frame(); fake.frames.emit(next); runCurrent(); vm.drawn(next); runCurrent()
        assertTrue(vm.state.value.interactive)
    }
    @Test fun successfulInputKeepsStreamAndConsumesSequencesInOrder() = run { fake, vm ->
        val frame = fake.frame(); fake.frames.emit(frame); runCurrent(); vm.drawn(frame); runCurrent()
        val epoch = fake.epoch
        val actions = listOf(ScreenAction(ScreenActionKind.Tap, x = 10, y = 20))
        vm.input(frame, actions); runCurrent()
        assertTrue(vm.state.value.interactive, vm.state.value.toString())
        vm.input(frame, actions); runCurrent()
        assertEquals(listOf(0L, 1L), fake.inputs.map { it.input.sequence })
        assertEquals(epoch, fake.epoch)
    }
    @Test fun partialBatchIsNeverReplayedAndRequiresFreshDraw() = run { fake, vm ->
        val frame = fake.frame(); fake.frames.emit(frame); runCurrent(); vm.drawn(frame); runCurrent()
        fake.partial = true
        val actions = listOf(ScreenAction(ScreenActionKind.Tap, x = 10, y = 20))
        vm.input(frame, actions); vm.input(frame, actions); runCurrent()
        assertEquals(1, fake.inputs.size)
        assertTrue(vm.state.value.error!!.contains("0/1"))
        assertFalse(vm.state.value.interactive)
        vm.input(frame, actions); runCurrent(); assertEquals(1, fake.inputs.size)
    }
    @Test fun suspendedHoldAndAnotherDeviceNeverEnableInput() = run { fake, vm ->
        fake.control = ScreenControl(2, "other", suspended = true)
        vm.retry(); runCurrent()
        val frame = fake.frame(); fake.frames.emit(frame); runCurrent(); vm.drawn(frame); runCurrent()
        assertTrue(vm.state.value.control.suspended)
        assertFalse(vm.state.value.interactive)
        fake.control = ScreenControl(3, "other")
        vm.retry(); runCurrent()
        val fresh = fake.frame(); fake.frames.emit(fresh); runCurrent(); vm.drawn(fresh); runCurrent()
        assertFalse(vm.state.value.interactive)
    }
    @Test fun frameArrivingBeforeWatchReplyIsRetainedAndDuplicateDrawIsHarmless() = run { fake, vm ->
        fake.emitWhileStarting = true
        vm.retry(); runCurrent()
        assertNotNull(vm.state.value.frame)
        val frame = fake.frame()
        vm.drawn(frame); vm.drawn(frame); runCurrent()
        assertTrue(vm.state.value.interactive)
        assertNull(vm.state.value.error)
    }
    @Test fun leavingDestinationStopsWatchesWithoutHandback() = run { fake, vm ->
        val previous = fake.frame()
        vm.visible(false); runCurrent()
        assertTrue(fake.watches.isEmpty())
        assertEquals("device", fake.control.holder)
        fake.frames.emit(previous); runCurrent()
        assertNull(vm.state.value.frame)
        vm.visible(true); runCurrent()
        assertEquals(1, fake.watches.size)
        assertTrue(fake.epoch > 1)
        fake.frames.emit(previous); runCurrent()
        assertNull(vm.state.value.frame)
    }
    @Test fun controlGenerationChangeRequiresFreshDraw() = run { fake, vm ->
        val frame = fake.frame(); fake.frames.emit(frame); runCurrent(); vm.drawn(frame); runCurrent()
        fake.control = ScreenControl(2, "other")
        fake.changes.emit(null); runCurrent()
        assertNull(vm.state.value.displayed)
        assertFalse(vm.state.value.interactive)
    }
    @Test fun selectionRejectsForeignOwnerAndOldWatchFrame() = run { fake, vm ->
        val original = vm.state.value.selected
        vm.select(ScreenRef("browser", "foreign", BrowserOwner("a")))
        assertEquals(original, vm.state.value.selected)
        val second = ScreenRef("computer", "two")
        fake.screens = vm.state.value.screens + vm.state.value.screens.first().copy(ref = second)
        vm.retry(); runCurrent()
        val old = fake.frame()
        vm.select(second); runCurrent()
        fake.frames.emit(old); runCurrent()
        assertNull(vm.state.value.frame)
    }
}
