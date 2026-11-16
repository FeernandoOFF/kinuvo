package kinuvo.plugins.mcp

import kinuvo.server.plugin.Keychain
import kinuvo.testing.harness.TemporaryDirectories
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KeychainSignInsTest {
    private val temporaryDirectories = TemporaryDirectories()

    @AfterTest
    fun deleteTemporaryDirectories() = temporaryDirectories.close()

    private val dir = temporaryDirectories.create("mcp-auth").toFile()
    private val sandbox = SandboxSignIns(Path(dir.toString()))
    private val keychain = FakeKeychain()
    private val signIn = McpSignIn("https://mcp.example/mcp", "https://auth.example/token", "client", accessToken = "access", refreshToken = "refresh")

    @Test
    fun aFreshStoreReadsWhatTheKeychainHolds() = runBlocking {
        KeychainSignIns(keychain, sandbox).put("shared/linear", signIn)
        assertEquals(signIn, KeychainSignIns(keychain, sandbox).get("shared/linear"))
        assertTrue(dir.walkTopDown().none { it.isFile }, "no sign-in file is written")
        assertTrue("access" in keychain.value.orEmpty())
    }

    @Test
    fun removesOneSignInOrEveryOneUnderAPrefix() = runBlocking {
        val store = KeychainSignIns(keychain, sandbox)
        store.put("shared/a", signIn)
        store.put("agents/x/b", signIn)
        store.put("agents/x/c", signIn)
        store.put("agents/xy/d", signIn)
        store.remove("shared/a")
        store.removeAll("agents/x")
        assertNull(store.get("shared/a"))
        assertNull(store.get("agents/x/b"))
        assertNull(store.get("agents/x/c"))
        assertEquals(signIn, store.get("agents/xy/d"))
        store.removeAll("agents/xy")
        assertNull(keychain.value)
    }

    @Test
    fun movesASandboxSignInIntoTheKeychain() = runBlocking {
        sandbox.put("shared/linear", signIn)
        assertEquals(signIn, KeychainSignIns(keychain, sandbox).get("shared/linear"))
        assertNull(sandbox.get("shared/linear"))
        assertFalse(dir.walkTopDown().any { it.isFile })
        assertEquals(signIn, KeychainSignIns(keychain, sandbox).get("shared/linear"))
    }

    private class FakeKeychain : Keychain {
        var value: String? = null
        override val name = "Fake Keychain"
        override suspend fun read() = value
        override suspend fun write(token: String) { value = token }
        override suspend fun delete() { value = null }
    }
}
