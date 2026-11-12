package kinuvo.plugins.onepassword

import kinuvo.server.plugin.CliSecretProvider
import kinuvo.server.plugin.Keychain
import kinuvo.protocol.secret.SecretEntry
import kinuvo.protocol.secret.secretPath
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

/**
 * 1Password through `op`, signed in with a service account token (read-only when created so, and limited to the vaults it
 * was given). References are 1Password's own: `op://Vault/Item/field` or `op://Vault/Item/section/field`. `op` keeps its
 * config in the Server home's `1password/`, apart from the user's.
 */
class OnePassword internal constructor(home: File, env: () -> Map<String, String>, keychain: Keychain?, cli: String) :
    CliSecretProvider(home, cli, keychain, env) {
    constructor(home: File, env: () -> Map<String, String>, keychain: Keychain?) :
        this(home, env, keychain, executable("op", "/opt/homebrew/bin/op", "/usr/local/bin/op", "/usr/bin/op"))

    override val scheme = "op"
    override val name = "1Password"
    override val tokenVariable = "OP_SERVICE_ACCOUNT_TOKEN"
    override val install = "brew install 1password-cli"
    override val tokenHelp = "Add a service account token from 1Password → Developer → Service accounts"
    private val configDir = File(home, "1password")

    override suspend fun resolve(uri: String, reason: String) = op(token(), "read", "--no-newline", "op://" + secretPath(uri).joinToString("/"))

    override fun parse(pasted: String) = pasted.trim().also {
        require(it.startsWith("ops_") && it.none(Char::isWhitespace)) { "Paste the service account token; it starts with ops_" }
    }

    override suspend fun verify(token: String) { op(token, "vault", "list", "--format", "json") }

    override fun found(entries: List<SecretEntry>) = "${entries.size} vaults shared with Kinuvo"

    override suspend fun browse(path: List<String>): List<SecretEntry> = when (path.size) {
        0 -> json(op(token(), "vault", "list", "--format", "json")).map { SecretEntry(it.text("name")) }
        1 -> json(op(token(), "item", "list", "--vault", path[0], "--format", "json"))
            .map { SecretEntry(it.text("title"), it.text("category").lowercase()) }.sortedBy { it.name.lowercase() }
        2 -> fields(Json.parseToJsonElement(op(token(), "item", "get", path[1], "--vault", path[0], "--format", "json")).jsonObject)
        else -> emptyList()
    }

    /** An item's field names, as `section/field` when in a section; values are dropped here, never returned. */
    private fun fields(item: JsonObject) = (item["fields"] as? JsonArray).orEmpty().mapNotNull { element ->
        val field = element as? JsonObject ?: return@mapNotNull null
        val label = field.text("label").ifEmpty { field.text("id") }.ifEmpty { return@mapNotNull null }
        val section = (field["section"] as? JsonObject)?.text("label").orEmpty()
        SecretEntry(if (section.isEmpty()) label else "$section/$label", field.text("type").lowercase())
    }.distinctBy { it.name }

    /** stdout of `op` [args] signed in with [token]. */
    private suspend fun op(token: String, vararg args: String): String {
        // op refuses a config folder others can read.
        if (configDir.mkdirs()) runCatching { Files.setPosixFilePermissions(configDir.toPath(), PosixFilePermissions.fromString("rwx------")) }
        return exec(args.toList(), mapOf(tokenVariable to token, "OP_CONFIG_DIR" to configDir.path)).value("op")
    }

    private fun json(text: String) = Json.parseToJsonElement(text).jsonArray

    private fun JsonElement.text(key: String) = jsonObject[key]?.jsonPrimitive?.contentOrNull.orEmpty()
}
