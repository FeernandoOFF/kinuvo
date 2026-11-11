package kinuvo.server

import kinuvo.protocol.SurfaceImage

internal const val MAX_SURFACE_IMAGE_BYTES = 5 * 1024 * 1024
internal val SURFACE_IMAGE_TYPES = setOf("image/png", "image/jpeg", "image/webp", "image/gif", "image/avif", "image/bmp")
// Some hosts, e.g. Wikimedia, refuse requests without a descriptive User-Agent.
internal const val SURFACE_IMAGE_USER_AGENT = "Kinuvo/1.0 (+https://github.com/FeernandoOFF/kinuvo)"

internal expect suspend fun fetchSurfaceImage(url: String): SurfaceImage
