package kinuvo.harness.server.testing

import kinuvo.protocol.Frame
import kinuvo.protocol.Method
import kinuvo.protocol.ProtocolJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeout

/** Records the requests clients send to a [TestServer], for call-count assertions. */
class RequestSpy {
    private val seen = MutableStateFlow(emptyList<Frame.Request>())

    val requests: List<Frame.Request> get() = seen.value
    val methods: List<String> get() = requests.map { it.method }

    internal fun record(frame: Frame) {
        if (frame is Frame.Request) seen.update { it + frame }
    }

    fun count(method: String) = requests.count { it.method == method }

    fun count(method: Method<*, *>) = count(method.name)

    fun <P> params(method: Method<P, *>): List<P> =
        requests.filter { it.method == method.name }.map { ProtocolJson.decodeFromJsonElement(method.params, it.params) }

    /** Waits for at least [count] requests of [method], for requests still in flight when a test asserts. */
    suspend fun await(method: Method<*, *>, count: Int = 1, timeoutMillis: Long = 5_000) {
        withTimeout(timeoutMillis) { seen.first { requests -> requests.count { it.method == method.name } >= count } }
    }

    fun clear() = seen.update { emptyList() }
}
