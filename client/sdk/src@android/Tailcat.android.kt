package kinuvo.client

import kinuvo.transport.tailcat.Tailcat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

actual fun platformTailcatForwarder(): TailcatForwarder = object : TailcatForwarder {
    override val supported = try { Tailcat.load(); true } catch (_: LinkageError) { false } catch (_: Exception) { false }
    override suspend fun open(address: String): TailcatForward = withContext(Dispatchers.IO) {
        val tunnel = try { Tailcat.load().forward(address) }
        catch (e: LinkageError) { throw IllegalStateException("Tailcat native library could not be loaded", e) }
        object : TailcatForward {
            override val port = requireNotNull(tunnel.port)
            override suspend fun close() = withContext(Dispatchers.IO) { tunnel.close() }
        }
    }
}
