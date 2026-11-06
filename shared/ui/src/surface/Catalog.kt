package kinuvo.ui.surface

import dev.ynagai.a2ui.compose.BasicCatalog
import dev.ynagai.a2ui.core.protocol.A2uiJson
import dev.ynagai.a2ui.core.protocol.CatalogDefinition
import kinuvo.app.surface.surfaceCatalog
import kinuvo.protocol.ProtocolJson
import kinuvo.protocol.UiCatalog
import kotlinx.serialization.json.*

internal val kinuvoCatalogDefinition: CatalogDefinition = surfaceCatalog(BasicCatalog.definition)
internal val surfaceCatalogDefinitions = listOf(BasicCatalog.definition, kinuvoCatalogDefinition)

internal val surfaceCatalogs = surfaceCatalogDefinitions.map { definition ->
    ProtocolJson.decodeFromJsonElement(UiCatalog.serializer(), A2uiJson.strict.encodeToJsonElement(CatalogDefinition.serializer(), definition))
}
