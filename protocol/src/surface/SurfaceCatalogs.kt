package kinuvo.protocol.surface

import kotlinx.serialization.Serializable

@Serializable
data class SurfaceCatalogsParams(val catalogs: List<UiCatalog>)
