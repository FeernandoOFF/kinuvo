package kinuvo.protocol

import kotlin.test.*

class ImageModelTest {
    @Test fun outputModalitiesRoundTripAndOldCatalogsDecode() {
        val model = ModelInfo("image", output = listOf("image", "text"))
        assertEquals(model, ProtocolJson.decodeFromString(ModelInfo.serializer(), ProtocolJson.encodeToString(ModelInfo.serializer(), model)))
        assertNull(ProtocolJson.decodeFromString(ModelInfo.serializer(), """{"id":"old"}""").output)
        val ref = ProviderModelRef("custom/router", "image")
        assertEquals(ref, ProtocolJson.decodeFromString(ProviderModelRef.serializer(), ProtocolJson.encodeToString(ProviderModelRef.serializer(), ref)))
    }
}
