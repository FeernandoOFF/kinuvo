package kinuvo.ui

import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import kotlin.test.*

class DesktopCredentialsTest {
    @Test
    fun preferencesAreOwnerOnly() {
        val dir = Files.createTempDirectory("private-preferences").toFile()
        FilePreferences(dir)["servers"] = "[]"
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(dir.resolve("desktop-servers.json").toPath()))
    }

    @Test
    fun macKeychainRoundTripSurvivesAStoreRestartWithoutPlaintextFiles() {
        if (!System.getProperty("os.name").lowercase().contains("mac")) return
        val dir = Files.createTempDirectory("secure-devices").toFile()
        val server = "wss://test-${UUID.randomUUID()}.invalid/ws"
        val token = UUID.randomUUID().toString().replace("-", "")
        val credentials = DesktopCredentials(dir)
        try {
            assertNull(credentials.read(server))
            credentials.write(server, token)
            assertEquals(token, DesktopCredentials(dir).read(server))
            assertTrue(dir.listFiles().orEmpty().none { it.readText().contains(token) })
        } finally { credentials.delete(server) }
        assertNull(DesktopCredentials(dir).read(server))
    }
}
