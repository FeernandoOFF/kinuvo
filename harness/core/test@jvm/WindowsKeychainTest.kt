package kinuvo.harness.core

import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kinuvo.protocol.Platform
import kinuvo.protocol.currentPlatform
import java.nio.file.Files
import java.util.Base64
import kotlin.test.*

class WindowsKeychainTest {
    @Test
    fun persistsOnlyCiphertextAndPassesTokensThroughStdin() = runTest {
        val dir = Files.createTempDirectory("windows-keychain").toFile()
        val file = dir.resolve("token.enc")
        val token = "a token with spaces and unicode: café 🔑"
        val encrypted = Base64.getEncoder().encodeToString("encrypted".toByteArray())
        val execute: suspend (List<String>, String) -> ProcessOutput = { command, input ->
            assertEquals("powershell.exe", command.first())
            assertTrue(command.none { token in it || Base64.getEncoder().encodeToString(token.toByteArray()) in it })
            assertTrue("DataProtectionScope]::CurrentUser" in command.last())
            if ("::Protect(" in command.last()) {
                assertEquals(token, String(Base64.getDecoder().decode(input)))
                ProcessOutput(0, "$encrypted\r\n", "")
            } else {
                assertEquals(encrypted, input)
                ProcessOutput(0, Base64.getEncoder().encodeToString(token.toByteArray()), "")
            }
        }
        try {
            val store = WindowsKeychain(Path(file.path), execute)
            assertNull(store.read())
            store.write(token)
            assertEquals(encrypted, file.readText())
            assertEquals(token, WindowsKeychain(Path(file.path), execute).read())
            store.delete()
            assertFalse(file.exists())
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun protectionFailureDoesNotOverwriteTheSavedToken() = runTest {
        val dir = Files.createTempDirectory("windows-keychain").toFile()
        val file = dir.resolve("token.enc").apply { writeText("previous-ciphertext") }
        try {
            val store = WindowsKeychain(Path(file.path)) { _, _ -> ProcessOutput(1, "", "failed") }
            assertFailsWith<IllegalStateException> { store.write("new-token") }
            assertEquals("previous-ciphertext", file.readText())
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun nativeWindowsStoreRoundTrip() = runTest {
        if (currentPlatform != Platform.Windows) return@runTest
        val dir = Files.createTempDirectory("windows-keychain").toFile()
        val file = dir.resolve("token.enc")
        val store = WindowsKeychain(Path(file.path))
        try {
            assertNotNull(Keychain.system("kinuvo-test", "windows"))
            store.write("test-token")
            assertFalse("test-token" in file.readText())
            assertEquals("test-token", WindowsKeychain(Path(file.path)).read())
            store.delete()
            assertNull(store.read())
        } finally { dir.deleteRecursively() }
    }
}
