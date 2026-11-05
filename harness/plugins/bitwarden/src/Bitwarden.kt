package kinuvo.harness.bitwarden

import kinuvo.harness.core.CliSecretProvider
import kinuvo.harness.core.Keychain
import kinuvo.protocol.SecretEntry
import kinuvo.protocol.secretPath
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * Bitwarden Secrets Manager through `bws`, signed in with a machine account access token (limited to the projects it was
 * given, read-only when granted so). References name a project and a secret's key, `bws://Project/KEY`, or a secret's id,
 * `bws://<id>`. `BWS_SERVER_URL` in [env] picks a region or a self-hosted server.
 */
class Bitwarden internal constructor(home: File, private val env: () -> Map<String, String>, keychain: Keychain?, cli: String) :
    CliSecretProvider(home, cli, keychain, env) {
    constructor(home: File, env: () -> Map<String, String>) : this(home, env, Keychain.system("kinuvo", "bitwarden"),
        executable("bws", "${System.getProperty("user.home")}/.cargo/bin/bws", "/opt/homebrew/bin/bws", "/usr/local/bin/bws"))

    override val scheme = "bws"
    override val name = "Bitwarden Secrets Manager"
    override val tokenVariable = "BWS_ACCESS_TOKEN"
    override val install = "download bws from github.com/bitwarden/sdk-sm/releases, or cargo install bws"
    override val tokenHelp = "Add a machine account access token from Secrets Manager → Machine accounts"
    override val levels = 2

    override suspend fun resolve(uri: String, reason: String): String {
        val path = secretPath(uri)
        val token = token()
        val secret = when (path.size) {
            1 -> Json.parseToJsonElement(bws(token, "secret", "get", path[0])).jsonObject
            2 -> secrets(token, project(token, path[0])).filter { it.text("key") == path[1] }.let {
                check(it.size <= 1) { "${it.size} secrets in ${path[0]} have the key ${path[1]}; refer to one by its id" }
                it.singleOrNull() ?: error("No secret ${path[1]} in ${path[0]}")
            }
            else -> error("Write bws://Project/KEY or bws://<secret id>")
        }
        return secret.text("value")
    }

    override suspend fun verify(token: String) { bws(token, "project", "list") }

    override fun found(entries: List<SecretEntry>) = "${entries.size} projects shared with Kinuvo"

    /** Projects, then the keys of a project's secrets; values are dropped here, never returned. */
    override suspend fun browse(path: List<String>): List<SecretEntry> {
        val token = token()
        return when (path.size) {
            0 -> projects(token).map { SecretEntry(it.text("name")) }
            1 -> secrets(token, project(token, path[0])).map { SecretEntry(it.text("key")) }
            else -> emptyList()
        }.sortedBy { it.name.lowercase() }
    }

    private suspend fun projects(token: String) = Json.parseToJsonElement(bws(token, "project", "list")).jsonArray

    /** The id of the project named [name] (or with that id). */
    private suspend fun project(token: String, name: String): String {
        val matches = projects(token).filter { it.text("name") == name || it.text("id") == name }
        check(matches.size <= 1) { "${matches.size} projects are named $name; refer to its secrets by id" }
        return matches.singleOrNull()?.text("id") ?: error("No project $name shared with Kinuvo")
    }

    private suspend fun secrets(token: String, projectId: String) =
        (Json.parseToJsonElement(bws(token, "secret", "list", projectId)) as JsonArray).map { it.jsonObject }

    /** stdout of `bws` [args] as JSON, signed in with [token]. */
    private suspend fun bws(token: String, vararg args: String): String {
        val server = env()[SERVER]?.takeIf { it.isNotBlank() }?.let { mapOf(SERVER to it) }.orEmpty()
        return exec(args.toList() + listOf("--output", "json"), mapOf(tokenVariable to token) + server).value("bws")
    }

    private fun JsonElement.text(key: String) = jsonObject[key]?.jsonPrimitive?.contentOrNull.orEmpty()

    private companion object {
        const val SERVER = "BWS_SERVER_URL"
    }
}
