package kinuvo.testing.server

import com.lemonappdev.konsist.api.Konsist
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class ArchitectureTest {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "project.yaml").isFile }

    // Explicit paths avoid project discovery, which expects .git to be a directory.
    private fun sources(directory: String) = File(root, directory).walkTopDown()
        .onEnter { it.name != "build" && !it.name.startsWith(".") }
        .filter { it.isDirectory && (it.name == "src" || it.name.startsWith("src@")) }
        .flatMap { Konsist.scopeFromExternalDirectory(it.absolutePath).files.asSequence() }.toList()

    @Test fun clientsImportNoServerOrHarness() {
        val files = sources("client")
        assertTrue(files.isNotEmpty())
        for (file in files) {
            assertTrue(file.imports.none { it.name.startsWith("kinuvo.server.") || it.name.startsWith("kinuvo.harness.") }, file.path)
        }
    }

    @Test fun protocolImportsNoOtherKinuvoLayer() {
        val files = sources("protocol")
        assertTrue(files.isNotEmpty())
        for (file in files) {
            assertTrue(file.imports.none { it.name.startsWith("kinuvo.") && !it.name.startsWith("kinuvo.protocol.") }, file.path)
        }
    }

    @Test fun viewModelsDoNotImportFrameTransport() {
        val files = sources("client/app").filter { it.name.substringBeforeLast(".").endsWith("ViewModel") }
        assertTrue(files.isNotEmpty())
        for (file in files) {
            assertTrue(file.imports.none { it.name in setOf("kinuvo.client.FrameConnection", "kinuvo.protocol.FrameConnection") }, file.path)
        }
    }
}
