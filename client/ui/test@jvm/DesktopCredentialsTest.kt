package kinuvo.ui

import kinuvo.testing.harness.TemporaryDirectories
import kinuvo.protocol.currentPlatform
import kinuvo.protocol.device.Platform
import kinuvo.protocol.device.currentPlatform
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import kotlin.test.*

class DesktopCredentialsTest {
    private val temporaryDirectories = TemporaryDirectories()

    @AfterTest
    fun deleteTemporaryDirectories() = temporaryDirectories.close()

    @Test
    fun preferencesAreOwnerOnly() {
        val dir = temporaryDirectories.create("private-preferences").toFile()
        FilePreferences(dir)["servers"] = "[]"
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(dir.resolve("desktop.json").toPath()))
    }

    @Test
    fun macKeychainRoundTripSurvivesAStoreRestartWithoutPlaintextFiles() {
        if (currentPlatform != Platform.MacOS) return
        val dir = temporaryDirectories.create("secure-devices").toFile()
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
