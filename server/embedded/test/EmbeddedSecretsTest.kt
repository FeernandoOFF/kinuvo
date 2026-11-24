package kinuvo.server.embedded

import kinuvo.server.plugin.children
import kinuvo.server.plugin.createDirectories
import kinuvo.server.plugin.deleteTree
import kinuvo.server.plugin.exists
import kinuvo.server.plugin.writeAtomically
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class EmbeddedSecretsTest {
    @Test fun eraseDeletesEverythingAndLeavesAnEmptyHome() {
        val home = Path(SystemTemporaryDirectory, "kinuvo-secrets-${Uuid.random()}")
        try {
            home.createDirectories()
            listOf("credentials/custom-key", "codex-auth.json", "mcp-oauth.enc", "mcp-auth/legacy.json",
                "agents/default/config.yaml", "agents/default/sessions/chat.json", "server-devices.json", "tailcat/state.json")
                .forEach { writeAtomically(Path(home, it), "saved") }
            eraseEmbeddedHome(home)
            assertTrue(home.exists())
            assertTrue(home.children().isEmpty())
            // Idempotent: resetting again, or before anything was ever written, doesn't fail.
            eraseEmbeddedHome(home)
        } finally { deleteTree(home) }
    }
}
