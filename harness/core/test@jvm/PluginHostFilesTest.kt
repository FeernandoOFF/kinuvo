package kinuvo.harness.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kinuvo.protocol.*
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import java.nio.file.Files
import kotlin.test.*

class PluginHostFilesTest {
    @Test fun fileGrantsEnforceModesTraversalAndSymlinks() = runTest {
        val dir = Files.createTempDirectory("plugin-host").toFile()
        val root = dir.resolve("root").apply { mkdirs() }
        val outside = dir.resolve("outside.txt").apply { writeText("private") }
        Files.createSymbolicLink(root.resolve("link").toPath(), outside.toPath())
        val instance = PluginInstanceId("example.files")
        val manifest = PluginManifest(PluginDefinition(instance.pluginId, "Files"),
            access = PluginAccess(files = mapOf("workspace" to PluginFileAccess.ReadWrite)))
        var grant = PluginFileGrant(Path(root.path))
        val http = HttpClient(MockEngine { respond("") })
        val runtime = PluginHosts(http)
        val host = runtime.bind(manifest, instance) { PluginGrants(instance, files = mapOf("workspace" to grant)) }
        try {
            assertFailsWith<PluginAccessDenied> { host.writeFile("workspace", "new.txt", byteArrayOf(1)) }
            for (path in listOf("../outside.txt", "/outside.txt", "folder/../../outside.txt", "C:\\outside.txt"))
                assertFailsWith<IllegalArgumentException> { host.readFile("workspace", path) }
            assertFailsWith<PluginAccessDenied> { host.readFile("workspace", "link") }
            assertFailsWith<PluginAccessDenied> { host.readFile("other", "new.txt") }
            grant = grant.copy(access = PluginFileAccess.ReadWrite)
            host.writeFile("workspace", "folder/new.txt", "hello".encodeToByteArray())
            assertEquals("hello", host.readFile("workspace", "folder/new.txt").decodeToString())
            val readOnly = runtime.bind(manifest.copy(access = PluginAccess(files = mapOf("workspace" to PluginFileAccess.Read))), instance) {
                PluginGrants(instance, files = mapOf("workspace" to grant))
            }
            assertFailsWith<PluginAccessDenied> { readOnly.writeFile("workspace", "new.txt", byteArrayOf(1)) }
            assertEquals("private", outside.readText())
        } finally { runtime.close(); http.close(); dir.deleteRecursively() }
    }
}
