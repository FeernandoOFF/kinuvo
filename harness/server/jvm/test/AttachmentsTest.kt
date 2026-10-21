package kinuvo.harness.server

import kinuvo.harness.testing.*
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart

import ai.koog.prompt.streaming.StreamFrame
import kinuvo.client.KinuvoClient
import kinuvo.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.io.files.Path
import java.nio.file.Files
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.io.encoding.Base64
import kotlin.test.*

class AttachmentsTest {
    @Test
    fun sendsAttachmentOnlyAndReplaysAfterRestart() = runBlocking {
        val home = Files.createTempDirectory("attachment-server").toFile()
        val seen = CompletableDeferred<Message.User>()
        val harness = Harness(Path(home.path), modelFactory = { scriptedModel { prompt, _ ->
            val history = prompt.messages
            seen.complete(history.filterIsInstance<Message.User>().last())
            flowOf(StreamFrame.TextDelta("done"), StreamFrame.End())
        } })
        val server = HarnessServer(harness)
        val port = server.start("127.0.0.1", 0, schedule = false)
        val client = KinuvoClient("ws://127.0.0.1:$port/ws", "test", pairingCode = server.auth.currentPairingCode())
        try { withTimeout(30_000) {
            client.connect()
            val a = client.call(Api.UploadAttachment, UploadAttachmentParams("note.md", Base64.encode("read me".encodeToByteArray())))
            val sent = client.call(Api.Send, SendParams("default", text = "", attachments = listOf(a.id)))
            assertEquals(1, seen.await().parts.filterIsInstance<MessagePart.Attachment>().size)
            assertEquals("read me", Base64.decode(client.call(Api.Attachment, AttachmentRef(a.id)).base64).decodeToString())
            assertFails { client.call(Api.Send, SendParams("default", attachments = listOf("../bad"), text = "")) }
            assertFails { client.call(Api.Send, SendParams("default", attachments = List(5) { a.id }, text = "")) }
            assertFails { client.call(Api.Send, SendParams("default", text = "")) }
            client.close()
            server.stop()
            val restored = Harness(Path(home.path))
            val agent = restored.store.agents().first { it.id == "default" }
            assertEquals("note.md", restored.store.sessions(agent).first { it.id == sent.sessionId }.title)
            assertEquals(listOf(a), restored.store.entries(agent, sent.sessionId).filterIsInstance<SessionEntry.UserMessage>().single().attachments)
            assertEquals("read me", restored.attachments.bytes(a).decodeToString())
        } } finally { client.close(); server.stop(); home.deleteRecursively() }
    }

    @Test
    fun appliesExifRotationBeforeDroppingMetadata() {
        val raw = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(80, 40, BufferedImage.TYPE_INT_RGB), "jpeg", it) }.toByteArray()
        val tiff = byteArrayOf(73, 73, 42, 0, 8, 0, 0, 0, 1, 0, 18, 1, 3, 0, 1, 0, 0, 0, 6, 0, 0, 0, 0, 0, 0, 0)
        val exif = "Exif\u0000\u0000".encodeToByteArray() + tiff
        val tagged = raw.copyOfRange(0, 2) + byteArrayOf(255.toByte(), 225.toByte(), 0, (exif.size + 2).toByte()) + exif + raw.copyOfRange(2, raw.size)
        val prepared = prepareAttachment(UploadAttachmentParams("portrait.jpg", Base64.encode(tagged)))
        val image = ImageIO.read(ByteArrayInputStream(Base64.decode(prepared.base64)))
        assertEquals(40, image.width)
        assertEquals(80, image.height)
    }

    @Test
    fun boundsImagesAndWritesFreshPixelsWithoutMetadata() {
        val raw = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(3000, 1500, BufferedImage.TYPE_INT_RGB), "png", it) }.toByteArray()
        val prepared = prepareAttachment(UploadAttachmentParams("screenshot.png", Base64.encode(raw)))
        assertEquals("screenshot.jpg", prepared.name)
        val image = ImageIO.read(ByteArrayInputStream(Base64.decode(prepared.base64)))
        assertEquals(2048, image.width)
        assertEquals(1024, image.height)
        assertFails { prepareAttachment(UploadAttachmentParams("fake.png", Base64.encode("bad".encodeToByteArray()))) }
    }
}
