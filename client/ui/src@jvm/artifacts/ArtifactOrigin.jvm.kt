package kinuvo.ui.artifacts

internal actual fun openOrigin(site: ArtifactSite): ArtifactOrigin? = if (originId(site.id)) runCatching { ArtifactLoopback.open(site) }.getOrNull() else null

internal actual fun closeOrigin(origin: ArtifactOrigin) = ArtifactLoopback.close(origin)
