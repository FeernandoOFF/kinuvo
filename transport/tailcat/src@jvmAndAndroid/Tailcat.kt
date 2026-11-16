package kinuvo.transport.tailcat

import kotlinx.serialization.json.*
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ConcurrentHashMap

interface Tailcat {
    fun serve(state: File, port: Int): Tunnel
    fun forward(address: String): Tunnel
    fun reset(state: File)

    interface Tunnel : AutoCloseable {
        val address: String?
        val port: Int?
    }

    companion object {
        private val native by lazy { NativeTailcat() }
        fun load(): Tailcat = native
    }
}

private class NativeTailcat : Tailcat {
    private val tunnels = ConcurrentHashMap.newKeySet<Tailcat.Tunnel>()
    private val invoke = nativeCall()
    init { Runtime.getRuntime().addShutdownHook(Thread { tunnels.forEach { runCatching { it.close() } } }) }
    private fun call(method: String, params: JsonObject): JsonObject {
        val value = Json.parseToJsonElement(invoke(method, params.toString())).jsonObject
        value["error"]?.jsonPrimitive?.content?.let { throw IllegalStateException(it) }
        return value
    }

    private fun tunnel(method: String, params: JsonObject): Tailcat.Tunnel {
        val result = call(method, params)
        val handle = result.getValue("handle")
        return object : Tailcat.Tunnel {
            private val closed = AtomicBoolean()
            override val address = result["address"]?.jsonPrimitive?.content
            override val port = result["port"]?.jsonPrimitive?.int
            override fun close() {
                if (closed.compareAndSet(false, true)) {
                    try { call("stop", buildJsonObject { put("handle", handle) }) } finally { tunnels.remove(this) }
                }
            }
        }.also { tunnels.add(it) }
    }

    override fun serve(state: File, port: Int) = tunnel("serve", buildJsonObject { put("state", state.path); put("port", port) })
    override fun forward(address: String) = tunnel("forward", buildJsonObject { put("address", address) })
    override fun reset(state: File) { call("reset", buildJsonObject { put("state", state.path) }) }

}

internal expect fun nativeCall(): (String, String) -> String
