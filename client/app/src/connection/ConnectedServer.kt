package kinuvo.app.connection

import kinuvo.app.workflow.WorkflowRepository
import kinuvo.app.device.*
import kinuvo.app.session.*
import kinuvo.app.agent.*
import kinuvo.app.settings.*
import kinuvo.app.board.*
import kinuvo.app.knowledge.*
import kinuvo.app.routine.*
import kinuvo.app.team.TeamRepository

import kinuvo.app.surface.McpApps
import kinuvo.app.surface.SurfaceImages
import kinuvo.client.KinuvoClient
import kotlinx.coroutines.*
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/** Owns one Server's work and caches. Transport reconnects keep this lifetime; switching ends it. */
class ConnectedServer(notifications: Notifications, private val deviceAccess: DeviceAccess, push: PushChannel? = null, private val onDevice: OnDeviceSupport = OnDeviceSupport.All, prefs: Preferences? = null) {
    private class Lifetime(val client: KinuvoClient) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<Lifetime>
        val job = SupervisorJob()
        val ended = CompletableDeferred<Unit>()
    }

    private var lifetime: Lifetime? = null
    internal var generation: Long = 0
        private set
    internal val client get() = lifetime?.client
    internal val scope get() = CoroutineScope((lifetime?.job ?: Job().also { it.cancel() }) + Dispatchers.Main.immediate + (lifetime ?: EmptyCoroutineContext))
    /** Attached and detached in this order; every repository the Server owns is added by [owned]. */
    internal val bound = mutableListOf<ServerBound>()
    private fun <T : ServerBound> owned(value: T): T = value.also { bound += it }
    val surfaceImages = SurfaceImages(this)
    val mcpApps = McpApps(this)
    val sessions = owned(SessionRepository(notifications, this, prefs))
    val teams = owned(TeamRepository(this))
    val routines = owned(RoutineRepository(notifications, this))
    val workflows = owned(WorkflowRepository(this))
    val config = owned(ConfigRepository(this))
    val harnesses = owned(HarnessRepository(this))
    val board = owned(BoardRepository(notifications, this))
    val knowledge = owned(KnowledgeRepository(this))
    val devices = owned(DevicesRepository(this))
    private val push = owned(PushRegistration(this, push))

    internal fun offers(provider: String) = !local || onDevice.supports(provider)
    private var local = false

    fun open(client: KinuvoClient, address: String, serverId: String? = null) {
        close()
        generation++
        local = address.startsWith("local://")
        lifetime = Lifetime(client)
        serverId?.let(sessions::loadSettled)
        deviceAccess.attach(address, client)
    }

    fun activate() {
        val client = requireNotNull(client)
        bound.forEach { it.attach(client) }
    }

    /** Fills the Repositories from [snapshot] after [open], before the Server is reached. */
    fun restore(snapshot: Snapshot) {
        sessions.restore(snapshot.lists, snapshot.sessions)
        routines.restore(snapshot.routines)
        board.restore(BoardData(snapshot.taskLists, snapshot.tasks))
    }

    fun close() {
        val previous = lifetime
        lifetime = null
        if (previous != null) generation++
        previous?.ended?.cancel()
        previous?.job?.cancel()
        previous?.client?.close()
        surfaceImages.clear()
        mcpApps.clear()
        bound.forEach { it.detach() }
        deviceAccess.detach()
    }

    /** Pins a whole operation, including its follow-up calls and UI callbacks, to its original Server. */
    suspend fun <T> run(block: suspend () -> T): T = withContext(Dispatchers.Main.immediate) {
        val owner = currentCoroutineContext()[Lifetime] ?: lifetime ?: error("Not connected")
        currentCoroutineContext().ensureActive()
        check(owner)
        coroutineScope {
            val work = currentCoroutineContext().job
            val registration = owner.ended.invokeOnCompletion { work.cancel() }
            try {
                withContext(owner) { block() }.also { currentCoroutineContext().ensureActive(); check(owner) }
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                check(owner)
                throw e
            } finally { registration.dispose() }
        }
    }

    private fun check(owner: Lifetime) {
        if (lifetime !== owner || !owner.job.isActive) throw CancellationException("Server changed")
    }

    internal suspend fun isCurrent(): Boolean = currentCoroutineContext()[Lifetime]?.let { lifetime === it && it.job.isActive } == true

    internal suspend fun <T> call(block: suspend (KinuvoClient) -> T): T = run {
        block(requireNotNull(currentCoroutineContext()[Lifetime]).client)
    }

    internal fun bind(scope: CoroutineScope, client: KinuvoClient? = null) {
        val owner = lifetime
        if (owner == null) { scope.cancel(); return }
        if (client != null && owner.client !== client) { scope.cancel(); return }
        val registration = owner.ended.invokeOnCompletion { scope.cancel() }
        scope.coroutineContext.job.invokeOnCompletion { registration.dispose() }
    }
}

internal suspend inline fun <T> catching(block: () -> T): Result<T> = try {
    val result = block()
    currentCoroutineContext().ensureActive()
    Result.success(result)
} catch (e: CancellationException) { throw e }
catch (e: Exception) {
    currentCoroutineContext().ensureActive()
    Result.failure(e)
}
