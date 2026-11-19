package kinuvo.protocol.mcp

import kinuvo.protocol.agent.ConfigScope
import kotlin.test.*

class McpCatalogTest {
    @Test fun catalogHasUniqueSecureEntries() {
        assertEquals(McpCatalog.size, McpCatalog.map { it.id }.toSet().size)
        McpCatalog.forEach { preset ->
            assertTrue(preset.url.startsWith("https://"), preset.id)
            if (preset.auth == McpPresetAuth.OwnClient) assertTrue(preset.scopes.isNotEmpty(), preset.id)
            assertEquals(preset.url, preset.server().url)
            assertEquals(ConfigScope.Agent, preset.server().scope)
        }
    }
}
