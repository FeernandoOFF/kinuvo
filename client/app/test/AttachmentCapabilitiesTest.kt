package kinuvo.app

import kinuvo.app.session.ChatState
import kinuvo.protocol.*
import kotlin.test.*

class AttachmentCapabilitiesTest {
    private val server = ServerCapabilities("test", setOf(ServerFeature.Attachments))

    @Test
    fun legacyServersRequireNativeFileSupportForTextAttachments() {
        val caps = SessionCapabilities(server = server, inputs = mapOf(
            "text" to CapabilityState(), "image" to CapabilityState(),
            "file" to CapabilityState.unsupported("No native documents"),
        ))
        val state = ChatState(capabilities = caps)
        assertTrue(state.files!!.actionable)
        assertTrue(state.photos!!.actionable)
        for (mime in listOf("text/plain", "text/csv", "application/json")) {
            assertFalse(caps.attachment(mime).actionable)
            assertTrue(caps.copy(inputs = caps.inputs + ("file" to CapabilityState())).attachment(mime).actionable)
        }
        assertFalse(caps.attachment("application/pdf").actionable)
        val textOnly = caps.copy(inputs = caps.inputs + ("image" to CapabilityState.unsupported("No images")))
        assertFalse(ChatState(capabilities = textOnly).files!!.actionable)
    }

    @Test
    fun explicitTextHandlingEnablesFilesWithoutNativeDocuments() {
        val caps = SessionCapabilities(server = server, inputs = mapOf("file" to CapabilityState.unsupported("No native documents")),
            attachments = mapOf("text/plain" to CapabilityState(), "application/json" to CapabilityState()))
        assertTrue(ChatState(capabilities = caps).files!!.actionable)
        assertTrue(caps.attachment("text/plain").actionable)
        assertTrue(caps.attachment("application/json").actionable)
        assertFalse(caps.attachment("application/pdf").actionable)
    }

    @Test
    fun resolvedFormatsAndServerFeaturesControlThePicker() {
        val caps = SessionCapabilities(server = server, attachments = mapOf("application/pdf" to CapabilityState()))
        assertTrue(ChatState(capabilities = caps).files!!.actionable)
        assertFalse(ChatState(capabilities = caps).photos!!.actionable)
        assertFalse(caps.attachment("text/plain").actionable)
        assertFalse(ChatState(capabilities = caps.copy(server = ServerCapabilities("test"))).files!!.actionable)
        assertFalse(ChatState(capabilities = caps.copy(attachments = emptyMap())).files!!.actionable)
    }
}
