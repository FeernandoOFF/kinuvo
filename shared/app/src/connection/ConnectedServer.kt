package kinuvo.app.connection

import kinuvo.app.device.*
import kinuvo.app.session.*
import kinuvo.app.agent.*
import kinuvo.app.board.*
import kinuvo.app.knowledge.*
import kinuvo.app.routine.*

import kinuvo.client.KinuvoClient
import kotlinx.coroutines.*
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Owns one Server's work and caches. Transport reconnects keep this lifetime; switching ends it. */
class ConnectedServer(notifications: Notifications, private val deviceAccess: DeviceAccess) {
    private class Lifetime(val client: KinuvoClient) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<Lifetime>
        val job = SupervisorJob()
        val ended = CompletableDeferred<Unit>()
    }

    private var lifetime: Lifetime? = null
    internal var generation: Long = 0
        private set
    internal val client get() = lifetime?.client
    internal val scope get() = CoroutineScope((lifetime?.job ?: Job().also { it.cancel() }) + Dispatchers.Main.immediate + (lifetime ?: kotlin.coroutines.EmptyCoroutineContext))
    val sessions = SessionRepository(notifications, this)
    val routines = RoutineRepository(notifications, this)
    val config = ConfigRepository(this)
    val harnesses = HarnessRepository(this)
    val board = BoardRepository(notifications, this)
    val knowledge = KnowledgeRepository(this)
    val devices = DevicesRepository(this)

    fun open(client: KinuvoClient, address: String) {
        close()
        generation++
        lifetime = Lifetime(client)
        deviceAccess.attach(address, client)
    }

    fun activate() {
        val client = requireNotNull(client)
        sessions.attach(client)
        routines.attach(client)
        config.attach(client)
        harnesses.attach(client)
        board.attach(client)
        knowledge.attach(client)
        devices.attach(client)
    }

    fun close() {
        val previous = lifetime
        lifetime = null
        if (previous != null) generation++
        previous?.ended?.cancel()
        previous?.job?.cancel()
        previous?.client?.close()
        sessions.detach()
        routines.detach()
        config.detach()
        harnesses.detach()
        board.detach()
        knowledge.detach()
        devices.detach()
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
