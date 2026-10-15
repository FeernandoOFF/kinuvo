package kinuvo.app

import kinuvo.app.agent.*
import kotlin.test.*

class McpCatalogTest {
    @Test fun presetsRoundTripThroughTheirItem() {
        McpCatalog.forEach { assertEquals(it, presetOf(presetItem(it))) }
        assertNull(presetOf("Agent/notion"))
        assertNull(presetOf(null))
    }

    @Test fun presetsPrefillWhatTheirSignInNeeds() {
        val names = Regex("[A-Za-z0-9._-]{1,64}")
        assertTrue(McpCatalog.all { names.matches(it.id) && it.url.startsWith("https://") })
        assertEquals(McpCatalog.size, McpCatalog.map { it.id }.distinct().size)
        assertEquals("Bearer ", McpCatalog.first { it.auth == McpPresetAuth.Token }.server().headers["Authorization"])
        val google = McpCatalog.first { it.auth == McpPresetAuth.OwnClient }.server()
        assertTrue(google.oauth!!.scopes.isNotEmpty())
        assertNull(McpCatalog.first { it.auth == McpPresetAuth.OAuth }.server().oauth)
    }
}
