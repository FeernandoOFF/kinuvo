package kinuvo.testing.harness

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith

class TemporaryDirectoriesTest {
    @Test fun deletesNestedFilesWhenTheTestFailsWithoutFollowingLinks() {
        TemporaryDirectories().use { outside ->
            val target = outside.create("keep")
            val file = Files.writeString(target.resolve("keep.txt"), "keep")
            val temporary = TemporaryDirectories()
            val home = temporary.create("cleanup")
            assertFailsWith<IllegalStateException> {
                temporary.use {
                    Files.createDirectories(home.resolve("nested"))
                    Files.writeString(home.resolve("nested/data.txt"), "data")
                    Files.createSymbolicLink(home.resolve("link"), target)
                    error("test failure")
                }
            }
            assertFalse(Files.exists(home))
            assertEquals("keep", Files.readString(file))
            temporary.close()
        }
    }
}
