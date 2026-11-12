package kinuvo.server

import dev.ynagai.a2ui.core.protocol.A2uiJson
import dev.ynagai.a2ui.core.protocol.CatalogDefinition
import kinuvo.app.surface.surfaceCatalog
import kinuvo.server.tools.ShowUiTool
import kinuvo.protocol.ProtocolJson
import kinuvo.protocol.UiCatalog
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class ShowUiExamplesTest {
    private fun args(message: String) = ProtocolJson.parseToJsonElement("""{"messages":[$message]}""").jsonObject

    @Test fun editableWidgets() = runTest {
        val basic = A2uiJson.lenient.decodeFromString(CatalogDefinition.serializer(), checkNotNull(javaClass.getResource("/a2ui-basic.json")).readText())
        val definition = surfaceCatalog(basic)
        val catalog = ProtocolJson.decodeFromJsonElement(UiCatalog.serializer(), A2uiJson.strict.encodeToJsonElement(CatalogDefinition.serializer(), definition))
        val tool = ShowUiTool(listOf(catalog))
        for (name in listOf("editable-tasks", "weekly-timesheet", "examples/trip-budget", "examples/habit-tracker", "examples/recipe-scaler", "examples/workout-log", "examples/dinner-poll", "examples/invoice-builder")) {
            val raw = checkNotNull(javaClass.getResource("/$name.json")).readText()
            assertEquals("Shown to the user.", tool.execute(args(raw)))
        }
    }
}
