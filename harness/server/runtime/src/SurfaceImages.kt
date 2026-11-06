package kinuvo.harness.server

import kinuvo.protocol.SurfaceImage

internal expect suspend fun fetchSurfaceImage(url: String): SurfaceImage
