package kinuvo.plugins.voice

import kinuvo.server.plugin.models.voiceEngine
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class VoiceEngineTest {
    @Test fun downloadedLibrariesStartWorker() = runBlocking {
        val home = System.getenv("KINUVO_TEST_ENGINE_HOME")?.let(::File) ?: return@runBlocking
        val engine = voiceEngine(home)
        engine.ensure()
        val worker = Inference.bundled()
        val process = ProcessBuilder(worker.absolutePath, engine.folder.absolutePath, "probe").inheritIO().start()
        assertEquals(0, process.waitFor())
        println("Voice worker loaded downloaded sherpa/ONNX libraries and resolved all inference symbols")
    }
}
