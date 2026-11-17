package kinuvo.tailcat

import co.touchlab.stately.collections.ConcurrentMutableSet
import kinuvo.client.TailcatForward
import kinuvo.client.TailcatForwarder
import kinuvo.client.TailcatPairing
import kinuvo.server.plugin.TailcatInvite
import kinuvo.server.plugin.TailcatServing
import kinuvo.server.plugin.TailcatTunnel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlinx.serialization.json.*
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** The native TailPocket-based Tailcat library as both the Server's [TailcatServing] and the client's [TailcatForwarder]. */
@OptIn(ExperimentalAtomicApi::class)
object Tailcat : TailcatServing, TailcatForwarder {
    private val native by lazy { NativeTailcat() }
    private val operations = AtomicInt(0)

    override val supported by lazy { try { native; true } catch (_: Exception) { false } }

    override fun serve(state: Path, port: Int, name: String): TailcatTunnel {
        val handle = native.open("host_start", buildJsonObject { put("state", state.toString()); put("name", name); put("port", port) })
        return object : TailcatTunnel, AutoCloseable by handle {
            override fun invite(code: String, payload: String, ttlSeconds: Int): TailcatInvite {
                val result = native.call("host_pair", buildJsonObject {
                    put("handle", handle.id); put("code", code); put("payload", payload); put("ttl", ttlSeconds)
                })
                val pairing = result.getValue("pairing")
                return object : TailcatInvite {
                    override val text = result.getValue("invite").jsonPrimitive.content
                    override val expiresAt = result.getValue("expiresAt").jsonPrimitive.long
                    override val open get() = native.call("host_pair_open", buildJsonObject { put("handle", handle.id); put("pairing", pairing) })["open"]?.jsonPrimitive?.boolean == true
                    override fun close() { native.call("host_close_pair", buildJsonObject { put("handle", handle.id); put("pairing", pairing) }) }
                }
            }

            override fun phoneOf(code: String) = native.call("host_phone", buildJsonObject { put("handle", handle.id); put("code", code) })["phone"]?.jsonPrimitive?.content

            override fun peerAt(port: Int) = native.call("host_peer", buildJsonObject { put("handle", handle.id); put("port", port) })["phone"]?.jsonPrimitive?.content

            override fun revoke(phone: String) { native.call("host_revoke", buildJsonObject { put("handle", handle.id); put("phone", phone) }) }
        }
    }

    override fun reset(state: Path) { native.call("reset", buildJsonObject { put("state", state.toString()) }) }

    override fun revoke(state: Path, phone: String) { native.call("revoke", buildJsonObject { put("state", state.toString()); put("phone", phone) }) }

    override suspend fun pair(invite: String, device: String): TailcatPairing {
        val op = operations.addAndFetch(1).toLong()
        val result = coroutineScope {
            val call = async(blockingDispatcher) { native.call("pair", buildJsonObject { put("invite", invite); put("device", device); put("op", op) }) }
            try { call.await() }
            catch (e: CancellationException) {
                runCatching { native.call("cancel", buildJsonObject { put("op", op) }) }
                throw e
            }
        }
        return TailcatPairing(result.getValue("link").jsonPrimitive.content, result["payload"]?.jsonPrimitive?.content.orEmpty(), result["name"]?.jsonPrimitive?.content.orEmpty())
    }

    override suspend fun open(link: String): TailcatForward = withContext(blockingDispatcher) {
        val handle = native.open("forward", buildJsonObject { put("link", link) })
        object : TailcatForward {
            override val port = requireNotNull(handle.port)
            override suspend fun close() = withContext(blockingDispatcher) { handle.close() }
        }
    }
}

@OptIn(ExperimentalAtomicApi::class)
private class NativeTailcat {
    class Handle(val id: Long, val port: Int?, val onClose: () -> Unit) : AutoCloseable {
        override fun close() = onClose()
    }

    private val handles = ConcurrentMutableSet<Handle>()
    private val invoke = nativeCall()
    init { onShutdown { handles.toList().forEach { runCatching { it.close() } } } }

    fun call(method: String, params: JsonObject): JsonObject {
        val value = Json.parseToJsonElement(invoke(method, params.toString())).jsonObject
        value["error"]?.jsonPrimitive?.content?.let { throw IllegalStateException(it) }
        return value
    }

    fun open(method: String, params: JsonObject): Handle {
        val result = call(method, params)
        val id = result.getValue("handle").jsonPrimitive.long
        val closed = AtomicBoolean(false)
        lateinit var handle: Handle
        handle = Handle(id, result["port"]?.jsonPrimitive?.int) {
            if (closed.compareAndSet(false, true)) {
                try { call("stop", buildJsonObject { put("handle", id) }) } finally { handles.remove(handle) }
            }
        }
        return handle.also { handles.add(it) }
    }
}

internal expect fun nativeCall(): (String, String) -> String

internal expect fun onShutdown(action: () -> Unit)

internal expect val blockingDispatcher: CoroutineDispatcher
