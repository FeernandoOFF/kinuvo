package kinuvo.harness.server

import kinuvo.protocol.SurfaceImage

internal actual suspend fun fetchSurfaceImage(url: String): SurfaceImage =
    error("Surface image fetching requires a JVM or Android Server")
