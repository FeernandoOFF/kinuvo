package kinuvo.ui.connection

import kinuvo.app.connection.loopbackHttpResponse
import kinuvo.app.connection.loopbackMatchesState
import kinuvo.app.connection.loopbackRequestPath
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import platform.darwin.inet_pton
import platform.posix.AF_INET
import platform.posix.POLLIN
import platform.posix.SOCK_STREAM
import platform.posix.SOL_SOCKET
import platform.posix.SO_NOSIGPIPE
import platform.posix.SO_RCVTIMEO
import platform.posix.SO_REUSEADDR
import platform.posix.SO_SNDTIMEO
import platform.posix.accept
import platform.posix.bind
import platform.posix.close
import platform.posix.getsockname
import platform.posix.listen
import platform.posix.memset
import platform.posix.poll
import platform.posix.pollfd
import platform.posix.recv
import platform.posix.send
import platform.posix.setsockopt
import platform.posix.sockaddr_in
import platform.posix.socket
import platform.posix.socklen_tVar
import platform.posix.timeval

// Apple targets are little-endian; socket ports use network byte order.
private fun swapBytes16(value: Int): Int = ((value and 0xFF) shl 8) or ((value ushr 8) and 0xFF)

internal class LoopbackListener(val fd: Int, val port: Int) {
    @OptIn(ExperimentalForeignApi::class)
    fun close() { platform.posix.close(fd) }
}

@OptIn(ExperimentalForeignApi::class)
private fun loopbackSocket(): Int {
    val fd = socket(AF_INET, SOCK_STREAM, 0)
    check(fd >= 0) { "socket() failed" }
    memScoped {
        val reuse = alloc<IntVar>()
        reuse.value = 1
        setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, reuse.ptr, sizeOf<IntVar>().convert())
    }
    return fd
}

@OptIn(ExperimentalForeignApi::class)
private fun bindTo(fd: Int, port: Int): Boolean = memScoped {
    val addr = alloc<sockaddr_in>()
    memset(addr.ptr, 0, sizeOf<sockaddr_in>().convert())
    addr.sin_len = sizeOf<sockaddr_in>().convert()
    addr.sin_family = AF_INET.convert()
    addr.sin_port = swapBytes16(port).convert()
    check(inet_pton(AF_INET, "127.0.0.1", addr.sin_addr.ptr) == 1)
    bind(fd, addr.ptr.reinterpret(), sizeOf<sockaddr_in>().convert()) == 0
}

/** Binds to [preferredPort] on 127.0.0.1, falling back to an ephemeral port, and starts listening. */
@OptIn(ExperimentalForeignApi::class)
internal fun bindLoopbackListener(preferredPort: Int): LoopbackListener {
    val fd = loopbackSocket()
    try {
        val port = if (bindTo(fd, preferredPort)) preferredPort else {
            check(bindTo(fd, 0)) { "bind() failed" }
            memScoped {
                val addr = alloc<sockaddr_in>()
                val len = alloc<socklen_tVar>()
                len.value = sizeOf<sockaddr_in>().convert()
                check(getsockname(fd, addr.ptr.reinterpret(), len.ptr) == 0) { "getsockname() failed" }
                swapBytes16(addr.sin_port.toInt())
            }
        }
        check(listen(fd, 8) == 0) { "listen() failed" }
        return LoopbackListener(fd, port)
    } catch (failure: Throwable) {
        close(fd)
        throw failure
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun pollReadable(fd: Int, timeoutMs: Int): Boolean = memScoped {
    val pfd = alloc<pollfd>()
    pfd.fd = fd
    pfd.events = POLLIN.convert()
    poll(pfd.ptr, 1u, timeoutMs) > 0 && (pfd.revents.toInt() and POLLIN) != 0
}

@OptIn(ExperimentalForeignApi::class)
private fun configureClient(fd: Int, millis: Int) = memScoped {
    val tv = alloc<timeval>()
    tv.tv_sec = (millis / 1000).toLong()
    tv.tv_usec = (millis % 1000) * 1000
    check(setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, tv.ptr, sizeOf<timeval>().convert()) == 0)
    check(setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, tv.ptr, sizeOf<timeval>().convert()) == 0)
    val enabled = alloc<IntVar>()
    enabled.value = 1
    check(setsockopt(fd, SOL_SOCKET, SO_NOSIGPIPE, enabled.ptr, sizeOf<IntVar>().convert()) == 0)
}

@OptIn(ExperimentalForeignApi::class)
private fun readByte(fd: Int): Int {
    val buffer = ByteArray(1)
    val n = buffer.usePinned { recv(fd, it.addressOf(0), 1u, 0) }
    return if (n <= 0) -1 else buffer[0].toInt() and 0xFF
}

/** Reads one bounded, time-boxed HTTP request and replies with a small page; returns the matched callback URL, or null. */
@OptIn(ExperimentalForeignApi::class)
private fun handleConnection(client: Int, port: Int, state: String?): String? {
    configureClient(client, 1000)
    val deadline = TimeSource.Monotonic.markNow()
    var remaining = 16_384
    fun line(): String {
        val value = StringBuilder()
        while (true) {
            if (--remaining < 0 || deadline.elapsedNow() > 2.seconds) error("HTTP request is too large or slow")
            val c = readByte(client)
            if (c < 0) error("Incomplete HTTP request")
            if (c == 10) return value.toString().trimEnd('\r')
            value.append(c.toChar())
        }
    }
    val path = loopbackRequestPath(line()).orEmpty()
    while (line().isNotEmpty()) { }
    val callback = "http://127.0.0.1:$port$path"
    val ours = loopbackMatchesState(path, callback, state)
    val page = if (ours) "<!doctype html><title>Kinuvo</title><p>You can return to Kinuvo.</p>"
    else "<!doctype html><title>Kinuvo</title><p>This is not the sign-in Kinuvo is waiting for.</p>"
    val response = loopbackHttpResponse(ours, page, contentType = "text/html").encodeToByteArray()
    response.usePinned { bytes ->
        var sent = 0
        while (sent < response.size) {
            val count = send(client, bytes.addressOf(sent), (response.size - sent).convert(), 0)
            if (count <= 0) break
            sent += count.toInt()
        }
    }
    return callback.takeIf { ours }
}

/** Awaits the OAuth redirect on [listener], ignoring unrelated or malformed connections, until it matches [state] or [cancelled] turns true. */
@OptIn(ExperimentalForeignApi::class)
internal suspend fun awaitLoopbackCallback(listener: LoopbackListener, state: String?, cancelled: () -> Boolean): String? {
    while (true) {
        currentCoroutineContext().ensureActive()
        if (cancelled()) return null
        if (!pollReadable(listener.fd, 500)) continue
        val client = accept(listener.fd, null, null)
        if (client < 0) continue
        val callback = try {
            runCatching { handleConnection(client, listener.port, state) }.getOrNull()
        } finally { close(client) }
        currentCoroutineContext().ensureActive()
        if (callback != null) return callback.takeUnless { cancelled() }
    }
}
