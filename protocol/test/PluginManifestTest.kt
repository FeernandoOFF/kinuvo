package kinuvo.protocol

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.*

class PluginManifestTest {
    private val manifest = PluginManifest(PluginDefinition("example.notes", "Notes"),
        tools = listOf(PluginToolDefinition("search", "Find notes", buildJsonObject { put("type", "object") }, Risk.Low)),
        providers = listOf(PluginProviderDefinition("notes", "Notes", PluginProviderKind.Knowledge,
            SettingsSchema(listOf(SettingsField("folder", "Folder", required = true))))),
        access = PluginAccess(network = setOf("server"), files = mapOf("notes" to PluginFileAccess.Read), secrets = setOf("token")))

    @Test fun manifestsRoundTripWithoutHostBindings() {
        manifest.checkSupported()
        val encoded = ProtocolJson.encodeToString(PluginManifest.serializer(), manifest)
        assertEquals(manifest, ProtocolJson.decodeFromString(PluginManifest.serializer(), encoded))
        assertFalse("https://" in encoded)
        assertEquals(setOf("token"), manifest.access.secrets)
    }

    @Test fun unsupportedVersionsAndAmbiguousDeclarationsAreRejected() {
        for (invalid in listOf(manifest.copy(formatVersion = 2), manifest.copy(tools = manifest.tools + manifest.tools),
            manifest.copy(providers = manifest.providers + manifest.providers), manifest.copy(access = PluginAccess(network = setOf(""))))) {
            assertFailsWith<IllegalArgumentException> { invalid.checkSupported() }
        }
    }
}
