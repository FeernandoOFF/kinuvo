package kinuvo.testing.harness

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.FileVisitResult
import java.nio.file.attribute.BasicFileAttributes
import java.io.IOException

class TemporaryDirectories : AutoCloseable {
    private val directories = mutableListOf<Path>()

    fun create(prefix: String): Path = Files.createTempDirectory(prefix).toAbsolutePath().normalize().also { directories.add(it) }

    fun create(parent: Path, prefix: String): Path = Files.createTempDirectory(parent, prefix).toAbsolutePath().normalize().also { directories.add(it) }

    override fun close() {
        directories.asReversed().filter { Files.exists(it) }.forEach { directory ->
            Files.walkFileTree(directory, object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
                    Files.delete(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path, error: IOException?): FileVisitResult {
                    if (error != null) throw error
                    Files.delete(dir)
                    return FileVisitResult.CONTINUE
                }
            })
        }
        directories.clear()
    }
}
