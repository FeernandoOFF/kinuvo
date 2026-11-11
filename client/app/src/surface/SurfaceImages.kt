package kinuvo.app.surface

import kinuvo.app.connection.ConnectedServer
import kinuvo.protocol.Api
import kinuvo.protocol.surface.SurfaceImage
import kinuvo.protocol.surface.SurfaceImageParams
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async

class SurfaceImages(private val server: ConnectedServer) {
    private val cache = LinkedHashMap<String, Deferred<SurfaceImage>>()

    suspend fun get(url: String): SurfaceImage = server.run {
        cache.getOrPut(url) {
            if (cache.size >= 32) cache.remove(cache.keys.first())
            server.scope.async { server.call { it.call(Api.SurfaceImage, SurfaceImageParams(url)) } }
        }.await()
    }

    internal fun clear() { cache.clear() }
}
