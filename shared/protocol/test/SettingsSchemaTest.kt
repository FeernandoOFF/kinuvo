package kinuvo.protocol

import kotlin.test.*

class SettingsSchemaTest {
    @Test fun unknownFieldTypesDoNotPreventDiscoveringOtherProviders() {
        val response = """{"kind":"Search","settings":{"provider":"future"},"providers":[
            {"id":"future","schema":{"version":2,"fields":[{"key":"timeout","label":"Timeout","type":"Duration","unit":"seconds"}]}},
            {"id":"ready","schema":{"fields":[{"key":"url","label":"URL","type":"Url"}]}}
        ]}"""
        val setup = ProtocolJson.decodeFromString(ServiceSetup.serializer(), response)
        assertEquals("Duration", setup.providers.first().schema.fields.single().type.value)
        assertFalse(setup.providers.first().schema.supported)
        assertTrue(setup.providers.last().schema.supported)
        assertEquals(setup, ProtocolJson.decodeFromString(ServiceSetup.serializer(), ProtocolJson.encodeToString(ServiceSetup.serializer(), setup)))
    }

    @Test fun credentialChangesRoundTrip() {
        val request = SaveServiceParams(ServiceKind.Search, ServiceSettings("search"), mapOf(
            "one" to SecretUpdate.Keep, "two" to SecretUpdate.Replace("\${API_KEY}"), "three" to SecretUpdate.Remove))
        assertEquals(request, ProtocolJson.decodeFromString(SaveServiceParams.serializer(), ProtocolJson.encodeToString(SaveServiceParams.serializer(), request)))

    }
}
