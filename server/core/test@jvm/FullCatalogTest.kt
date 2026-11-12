package kinuvo.server

import kinuvo.server.tools.ShowUiTool
import kinuvo.protocol.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class FullCatalogTest {
    private val catalog = ProtocolJson.decodeFromString(UiCatalog.serializer(),
        javaClass.getResource("/a2ui-basic.json")!!.readText())
    @Test fun acceptsEveryBasicComponent() = runTest {
        val tool = ShowUiTool(listOf(catalog))
        val message = ProtocolJson.parseToJsonElement("""{"version":"v1.0","createSurface":{"surfaceId":"all","catalogId":"https://a2ui.org/specification/v1_0/catalogs/basic/catalog.json","components":[{"id":"root","component":"Card","child":"body"},{"id":"body","component":"Column","align":"stretch","children":["heading","row","list","divider","field","choice","check","slider","date","tabs","modal","video","audio","save"]},{"id":"heading","component":"Text","text":"## Native Surface\nAll 18 basic components"},{"id":"row","component":"Row","align":"center","children":["icon","image"]},{"id":"icon","component":"Icon","name":"favorite"},{"id":"image","component":"Image","url":"https://example.com/image.png","description":"Preview image","fit":"cover","variant":"smallFeature"},{"id":"list","component":"List","direction":"horizontal","children":["item"]},{"id":"item","component":"Text","text":"A reusable list item","variant":"caption"},{"id":"divider","component":"Divider"},{"id":"field","component":"TextField","label":"Name","value":{"path":"/name"}},{"id":"choice","component":"ChoicePicker","label":"Focus","value":{"path":"/focus"},"displayStyle":"chips","filterable":true,"options":[{"label":"Create","value":"create"},{"label":"Plan","value":"plan"}]},{"id":"check","component":"CheckBox","label":"Send a reminder","value":{"path":"/remind"}},{"id":"slider","component":"Slider","label":"Progress","min":0,"max":100,"steps":4,"value":{"path":"/progress"}},{"id":"date","component":"DateTimeInput","label":"When","enableDate":true,"enableTime":true,"value":{"path":"/when"},"min":"2026-01-01T00:00:00Z","max":"2027-01-01T00:00:00Z"},{"id":"tabs","component":"Tabs","tabs":[{"title":"Overview","child":"overview"},{"title":"Details","child":"detail"}]},{"id":"overview","component":"Text","text":"Your plan is ready."},{"id":"detail","component":"Text","text":"Plan details"},{"id":"modal","component":"Modal","trigger":"trigger","content":"modalContent"},{"id":"trigger","component":"Button","child":"triggerLabel","action":{"event":{"name":"open"}}},{"id":"triggerLabel","component":"Text","text":"Review details"},{"id":"modalContent","component":"Text","text":"## Details\nMake time for what matters."},{"id":"video","component":"Video","url":"https://example.com/video.mp4"},{"id":"audio","component":"AudioPlayer","url":"https://example.com/audio.mp3","description":"A moment of calm"},{"id":"save","component":"Button","variant":"primary","child":"saveLabel","action":{"event":{"name":"save"}}},{"id":"saveLabel","component":"Text","text":"Save plan"}],"dataModel":{"name":"Alex","focus":["create"],"remind":true,"progress":50,"when":"2026-11-06T14:30"}}}""")
        assertEquals("Shown to the user.", tool.execute(buildJsonObject { put("messages", JsonArray(listOf(message))) }))
        val summary = ProtocolJson.parseToJsonElement(tool.descriptor.description.substringAfter("Catalogs:\n")).jsonObject
        assertNotNull(summary.getValue("components").jsonObject.getValue("Image").jsonObject.getValue("properties").jsonObject["description"])
        assertEquals(18, catalog.components.size)
        assertTrue(tool.descriptor.description.length < 30000)
        assertFalse("relative weight of this component" in tool.descriptor.description)
        println("SHOW_UI_COMPACT_CHARS=" + tool.descriptor.description.length)
        println("SHOW_UI_FULL_CATALOG_CHARS=" + ProtocolJson.encodeToString(UiCatalog.serializer(), catalog).length)
    }
}
