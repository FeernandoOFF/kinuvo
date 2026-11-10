package kinuvo.harness.codex

import kinuvo.server.plugin.GeneratedImage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.*

class CodexImagesLiveTest {
    @Test
    fun generatesAndEditsThroughChatGptLogin() {
        if (System.getenv("KINUVO_LIVE_IMAGES") != "1") return
        val auth = CodexAuth()
        val images = CodexImages(codexHttpClient(auth::tokens, MutableStateFlow(null)))
        val model = System.getenv("KINUVO_MODEL") ?: "gpt-6.1-sol"
        runBlocking {
            val sun = images.generate(model, "A small flat golden sun on a dark blue background").single()
            assertTrue(sun.bytes.size > 1000)
            File("build/live-sun.png").apply { parentFile.mkdirs() }.writeBytes(sun.bytes)
            val edited = images.generate(model, "Make the background bright red", listOf(GeneratedImage(sun.bytes))).single()
            File("build/live-sun-edited.png").writeBytes(edited.bytes)
            println("Generated ${sun.bytes.size} bytes, edited ${edited.bytes.size} bytes")
        }
    }
}
