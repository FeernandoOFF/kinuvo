package kinuvo.tailcat

import kinuvo.client.TailcatForward
import kinuvo.client.TailcatForwarder
import kinuvo.server.plugin.TailcatServing
import kinuvo.server.plugin.TailcatTunnel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlinx.serialization.json.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ConcurrentHashMap

/** The native Tailcat library as both the Server's [TailcatServing] and the client's [TailcatForwarder]. */
object Tailcat : TailcatServing, TailcatForwarder {
    private val native by lazy { NativeTailcat() }

    override val supported by lazy { try { native; true } catch (_: LinkageError) { false } catch (_: Exception) { false } }

    override fun serve(state: Path, port: Int): TailcatTunnel {
        val tunnel = native.open("serve", buildJsonObject { put("state", state.toString()); put("port", port) })
        return object : TailcatTunnel, AutoCloseable by tunnel { override val address = requireNotNull(tunnel.address) }
    }

    override fun reset(state: Path) { native.call("reset", buildJsonObject { put("state", state.toString()) }) }

    override suspend fun open(address: String): TailcatForward = withContext(Dispatchers.IO) {
        val tunnel = try { native.open("forward", buildJsonObject { put("address", address) }) }
        catch (e: LinkageError) { throw IllegalStateException("Tailcat native library could not be loaded", e) }
        object : TailcatForward {
            override val port = requireNotNull(tunnel.port)
            override suspend fun close() = withContext(Dispatchers.IO) { tunnel.close() }
        }
    }
}

private class NativeTailcat {
    class Handle(val address: String?, val port: Int?, val onClose: () -> Unit) : AutoCloseable {
        override fun close() = onClose()
    }

    private val tunnels = ConcurrentHashMap.newKeySet<Handle>()
    private val invoke = nativeCall()
    init { Runtime.getRuntime().addShutdownHook(Thread { tunnels.forEach { runCatching { it.close() } } }) }

    fun call(method: String, params: JsonObject): JsonObject {
        val value = Json.parseToJsonElement(invoke(method, params.toString())).jsonObject
        value["error"]?.jsonPrimitive?.content?.let { throw IllegalStateException(it) }
        return value
    }

    fun open(method: String, params: JsonObject): Handle {
        val result = call(method, params)
        val handle = result.getValue("handle")
        val closed = AtomicBoolean()
        lateinit var tunnel: Handle
        tunnel = Handle(result["address"]?.jsonPrimitive?.content, result["port"]?.jsonPrimitive?.int) {
            if (closed.compareAndSet(false, true)) {
                try { call("stop", buildJsonObject { put("handle", handle) }) } finally { tunnels.remove(tunnel) }
            }
        }
        return tunnel.also { tunnels.add(it) }
    }
}

internal expect fun nativeCall(): (String, String) -> String
