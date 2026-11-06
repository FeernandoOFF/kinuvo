package kinuvo.ui.surface

import dev.ynagai.a2ui.compose.BasicCatalog
import dev.ynagai.a2ui.core.protocol.A2uiJson
import dev.ynagai.a2ui.core.protocol.CatalogDefinition
import kinuvo.protocol.ProtocolJson
import kinuvo.protocol.UiCatalog
import kotlinx.serialization.json.*

internal val kinuvoCatalogDefinition: CatalogDefinition = BasicCatalog.definition

internal val kinuvoCatalog: UiCatalog = ProtocolJson.decodeFromJsonElement(
    UiCatalog.serializer(), A2uiJson.strict.encodeToJsonElement(CatalogDefinition.serializer(), kinuvoCatalogDefinition),
)
