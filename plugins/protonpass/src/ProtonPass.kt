package kinuvo.plugins.protonpass

import kotlinx.coroutines.CancellationException
import kinuvo.server.plugin.CliSecretProvider
import kinuvo.server.plugin.Keychain
import kinuvo.protocol.secret.SecretEntry
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * Proton Pass through `pass-cli`, signed in with an AI agent access token (read-only, limited to the vaults it was given,
 * and every read logged with its reason). Kinuvo keeps its own pass-cli session in the Server home's `protonpass/`, apart
 * from the user's, and signs in again when the session (2 hours) expires.
 */
class ProtonPass internal constructor(home: File, env: () -> Map<String, String>, keychain: Keychain?, cli: String) :
    CliSecretProvider(home, cli, keychain, env) {
    constructor(home: File, env: () -> Map<String, String>, keychain: Keychain?) :
        this(home, env, keychain, executable("pass-cli",
            "${System.getProperty("user.home")}/.local/bin/pass-cli", "/opt/homebrew/bin/pass-cli", "/usr/local/bin/pass-cli"))

    override val scheme = "pass"
    override val name = "Proton Pass"
    override val tokenVariable = "PROTON_PASS_PERSONAL_ACCESS_TOKEN"
    override val install = "brew install protonpass/tap/pass-cli"
    override val tokenHelp = "Add an AI agent access token from Proton Pass → Settings → Access tokens"
    private val sessionDir = File(home, "protonpass")
    private val lock = Mutex()

    override suspend fun resolve(uri: String, reason: String) = signedIn { pass("item", "view", uri, reason = reason) }.removeSuffix("\n")

    /** Takes the token itself, or the AI agent instructions Proton Pass gives with it. */
    override fun parse(pasted: String) = (Regex("""$tokenVariable="([^"]+)"""").find(pasted)?.groupValues?.get(1) ?: pasted).trim().also {
        require(it.isNotEmpty() && it.none(Char::isWhitespace)) { "Paste the access token, or the AI agent instructions that include it" }
    }

    /** Signs in and keeps the token in one step, so a read signing in again meanwhile can't use the old token. */
    override suspend fun connect(token: String) = lock.withLock { super.connect(token) }

    override suspend fun verify(token: String) = login(token)

    override fun found(entries: List<SecretEntry>) = "${entries.size} vaults shared with Kinuvo"

    override suspend fun signOut() = lock.withLock { pass("logout", check = false); Unit }

    override suspend fun browse(path: List<String>): List<SecretEntry> = when (path.size) {
        0 -> json(signedIn { pass("vault", "list", "--output", "json") })["vaults"]!!.jsonArray.map { SecretEntry(it.text("name")) }
        1 -> json(signedIn { pass("item", "list", "--vault-name", path[0], "--output", "json") })["items"]!!.jsonArray
            .map { SecretEntry(it.text("title"), it.text("item_type")) }.sortedBy { it.name.lowercase() }
        2 -> fields(json(signedIn {
            pass("item", "view", "--vault-name", path[0], "--item-title", path[1], "--output", "json", reason = "Kinuvo: listing field names to pick a secret")
        })["item"]!!.jsonObject["content"]!!.jsonObject)
        else -> emptyList()
    }

    /**
     * Field names of an item's content (values are dropped here, never returned): its type's own fields, extra fields,
     * and fields in sections as `Section.field`.
     */
    private fun fields(content: JsonObject): List<SecretEntry> {
        fun names(fields: JsonElement?, section: String = "") = (fields as? JsonArray).orEmpty().mapNotNull {
            (it as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull?.let { n -> if (section.isEmpty()) n else "$section.$n" }
        }
        fun sections(value: JsonElement?) = (value as? JsonArray).orEmpty().flatMap {
            val s = it as? JsonObject ?: return@flatMap emptyList()
            names(s["section_fields"], s["section_name"]?.jsonPrimitive?.contentOrNull.orEmpty())
        }
        val typed = content["content"]?.jsonObject?.values?.firstOrNull() as? JsonObject
        val own = typed.orEmpty().flatMap { (key, value) ->
            when {
                key == "urls" -> listOfNotNull("url".takeIf { (value as? JsonArray)?.isNotEmpty() == true })
                key.endsWith("sections") -> sections(value)
                key.startsWith("extra_") -> names(value)
                (value as? JsonPrimitive)?.isString == true && value.content.isNotEmpty() -> listOf(if (key == "totp_uri") "totp" else key)
                else -> emptyList()
            }
        }
        val note = listOfNotNull("note".takeIf { content["note"]?.jsonPrimitive?.contentOrNull?.isNotEmpty() == true })
        return (own + names(content["extra_fields"]) + note).distinct().map { SecretEntry(it) }
    }

    /** Runs [block], signing in again first when it failed because the session is gone. */
    private suspend fun signedIn(block: suspend () -> String): String {
        val first = runCatching { block() }.onFailure { if (it is CancellationException) throw it }
        if (first.isSuccess || pass("info", check = false).isNotEmpty()) return first.getOrThrow()
        return lock.withLock {
            // Another read may have signed in while this one waited.
            if (pass("info", check = false).isEmpty()) login(token())
            block()
        }
    }

    private suspend fun login(token: String) {
        pass("logout", check = false)
        exec(listOf("login"), base() + (tokenVariable to token)).value("pass-cli login")
    }

    /** stdout of pass-cli [args]; with [check] false, empty on failure. [reason] is required by agent tokens to read items. */
    private suspend fun pass(vararg args: String, reason: String? = null, check: Boolean = true): String {
        val output = try {
            exec(args.toList(), base() + listOfNotNull(reason?.let { REASON to it.take(300) }))
        } catch (e: IllegalStateException) {
            if (!check) return ""
            throw e
        }
        return if (check) output.value("pass-cli") else if (output.ok) output.out else ""
    }

    private fun base() = mapOf("PROTON_PASS_SESSION_DIR" to sessionDir.path)

    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject

    private fun JsonElement.text(key: String) = jsonObject[key]?.jsonPrimitive?.contentOrNull.orEmpty()

    private companion object {
        const val REASON = "PROTON_PASS_AGENT_REASON"
    }
}
