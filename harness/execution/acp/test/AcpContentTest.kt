@file:OptIn(com.agentclientprotocol.annotations.UnstableApi::class)
package kinuvo.execution.acp

import com.agentclientprotocol.model.v2.*
import kinuvo.execution.api.ExecutionInput
import kinuvo.protocol.*
import java.util.Base64
import kotlin.test.*

class AcpContentTest {
    @Test fun negotiatedImagesAndTextResourcesExcludeFiles() {
        assertTrue((null as PromptCapabilities?).supportedMimeTypes().isEmpty())
        val types = PromptCapabilities(image = PromptImageCapabilities(), embeddedContext = PromptEmbeddedContextCapabilities()).supportedMimeTypes()
        assertTrue("image/png" in types); assertTrue("application/json" in types); assertFalse("application/pdf" in types)
        val capabilities = ExecutionCapabilities(supportedMimeTypes = types)
        val document = AttachmentData(Attachment("d", "data.json", "application/json", 2), Base64.getEncoder().encodeToString("{}".toByteArray()))
        val image = AttachmentData(Attachment("i", "image.png", "image/png", 1), "AA==")
        val blocks = ExecutionInput("", listOf(image, document)).content(capabilities)
        assertIs<ContentBlock.Image>(blocks[0]); assertIs<ContentBlock.Resource>(blocks[1])
        assertFailsWith<IllegalArgumentException> { ExecutionInput("", listOf(image)).content(ExecutionCapabilities()) }
        assertFailsWith<IllegalArgumentException> { ExecutionInput("").content(capabilities) }
    }
}
