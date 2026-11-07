package kinuvo.harness.litert

import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class NativeEngineTest {
    @Test fun downloadsAndLoadsJni() = runBlocking {
        val home = System.getenv("KINUVO_TEST_ENGINE_HOME")?.let(::File) ?: return@runBlocking
        val engine = nativeEngine(home)
        engine.ensure {}
        assertTrue(engine.installed)
        engine.load()
        val loader = Class.forName("com.google.ai.edge.litertlm.NativeLibraryLoader")
        loader.getMethod("load").invoke(loader.getField("INSTANCE").get(null))
        println("Downloaded LiteRT JNI loaded and upstream nativeCheckLoaded passed")
    }
}
