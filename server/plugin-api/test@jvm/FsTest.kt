package kinuvo.server.plugin

import kinuvo.testing.harness.TemporaryDirectories
import kotlinx.io.files.Path
import java.nio.file.Files
import java.io.File
import kotlin.test.*

class FsTest {
    private val temporaryDirectories = TemporaryDirectories()

    @AfterTest fun cleanUp() = temporaryDirectories.close()

    @Test fun containmentUsesResolvedComponents() {
        val dir = temporaryDirectories.create("containment").toFile()
        val root = dir.resolve("agents").apply { mkdirs() }
        val child = root.resolve("default").apply { mkdirs() }
        assertTrue(isInside(Path(root.path), Path(root.path)))
        assertTrue(isInside(Path(root.path), Path(child.path)))
        assertTrue(isInside(Path(root.path), Path(child.resolve("missing/nested").path)))
        assertFalse(isInside(Path(root.path), Path(dir.resolve("agents-other").apply { mkdirs() }.path)))
        assertFalse(isInside(Path(root.path), Path(dir.resolve("agents-other/missing").path)))
        assertTrue(isInside(Path(dir.toPath().root.toString()), Path(child.path)))
        if (File.separatorChar == '\\') {
            assertTrue('\\' in root.path)
            assertTrue(isInside(Path(root.path.replace('\\', '/')), Path(child.path)))
        } else {
            val backslash = dir.resolve("agents\\escape").apply { mkdirs() }
            assertFalse(isInside(Path(root.path), Path(backslash.path)))
            assertFalse(isInside(Path(root.path), Path(backslash.resolve("missing").path)))
            val named = root.resolve("literal\\name").apply { mkdirs() }
            assertTrue(isInside(Path(named.path), Path(named.resolve("missing").path)))
        }
    }

    @Test fun symlinksAreResolvedForExistingAndMissingTargets() {
        val dir = temporaryDirectories.create("containment-links").toFile()
        val root = dir.resolve("agents").apply { mkdirs() }
        val outside = dir.resolve("outside").apply { mkdirs() }
        val inside = root.resolve("default").apply { mkdirs() }
        val escape = Files.createSymbolicLink(root.resolve("escape").toPath(), outside.toPath()).toFile()
        val alias = Files.createSymbolicLink(dir.resolve("alias").toPath(), root.toPath()).toFile()
        Files.createSymbolicLink(root.resolve("internal").toPath(), inside.toPath())
        assertFalse(isInside(Path(root.path), Path(escape.path)))
        assertFalse(isInside(Path(root.path), Path(escape.resolve("missing/nested").path)))
        assertTrue(isInside(Path(alias.path), Path(inside.path)))
        assertTrue(isInside(Path(root.path), Path(root.resolve("internal/missing").path)))
    }
}
