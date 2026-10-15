package kinuvo.harness.protonpass

import kinuvo.harness.core.Keychain
import kinuvo.harness.core.SecretProvider
import kinuvo.harness.core.runProcess
import kinuvo.protocol.SecretEntry
import kinuvo.protocol.SecretProviderStatus
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
import java.io.IOException

/**
 * Proton Pass through `pass-cli`, signed in with an AI agent access token (read-only, limited to the vaults it was
 * given, and every read logged with its reason). The token is kept in the OS keychain, else read from
 * `PROTON_PASS_PERSONAL_ACCESS_TOKEN` in [env] (Kinuvo's `.env` files, which aren't passed on to other processes). Kinuvo
 * keeps its own pass-cli session in [sessionDir], apart from the user's, and signs in again when the session (2 hours) expires.
 */
class ProtonPass internal constructor(
    private val sessionDir: File,
    private val env: () -> Map<String, String>,
    private val keychain: Keychain?,
    private val cli: String,
) : SecretProvider {
    constructor(sessionDir: File, env: () -> Map<String, String>) :
        this(sessionDir, env, Keychain.system("kinuvo", "protonpass"), executable())

    override val scheme = "pass"
    override val name = "Proton Pass"
    private val lock = Mutex()
    private val envFile = File(sessionDir.parentFile, ".env")
    private val storage get() = keychain?.name ?: "$TOKEN in $envFile"

    override suspend fun resolve(uri: String, reason: String) = signedIn { pass("item", "view", uri, reason = reason) }.removeSuffix("\n")

    override suspend fun status(): SecretProviderStatus {
        val status = SecretProviderStatus(scheme, name, tokenStorage = storage)
        if (!installed()) return status.copy(detail = "Install pass-cli on the Server: $INSTALL")
        if (token() == null) return status.copy(installed = true, detail = "Add an AI agent access token from Proton Pass → Settings → Access tokens")
        return runCatching { browse(emptyList()) }.fold(
            { status.copy(installed = true, connected = true, detail = "Signed in; ${it.size} vaults shared with Kinuvo") },
            { status.copy(installed = true, connected = true, detail = it.message.orEmpty()) },
        )
    }

    /** Takes the token itself, or the AI agent instructions Proton Pass gives with it. */
    override suspend fun connect(token: String) {
        val trimmed = (INSTRUCTIONS.find(token)?.groupValues?.get(1) ?: token).trim()
        require(trimmed.isNotEmpty() && trimmed.none { it.isWhitespace() }) { "Paste the access token, or the AI agent instructions that include it" }
        val keychain = checkNotNull(keychain) { "This Server has no keychain; set $TOKEN in $envFile instead" }
        check(installed()) { "Install pass-cli on the Server first: $INSTALL" }
        lock.withLock {
            login(trimmed)
            keychain.write(trimmed)
        }
    }

    override suspend fun disconnect() {
        lock.withLock {
            keychain?.delete()
            check(env()[TOKEN].isNullOrBlank()) { "Remove $TOKEN from $envFile to disconnect" }
            pass("logout", check = false)
        }
    }

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
        val first = runCatching { block() }
        if (first.isSuccess || pass("info", check = false).isNotEmpty()) return first.getOrThrow()
        return lock.withLock {
            // Another read may have signed in while this one waited.
            if (pass("info", check = false).isEmpty()) login(token() ?: error("Proton Pass isn't connected; add an access token in Settings → Server → Secrets"))
            block()
        }
    }

    private suspend fun login(token: String) {
        pass("logout", check = false)
        runProcess(listOf(cli, "login"), base() + (TOKEN to token)).value("pass-cli login")
    }

    private suspend fun token() = keychain?.read() ?: env()[TOKEN]?.takeIf { it.isNotBlank() }

    private suspend fun installed() = try {
        runProcess(listOf(cli, "--version")).ok
    } catch (_: IOException) {
        false
    }

    /** stdout of pass-cli [args]; with [check] false, empty on failure. [reason] is required by agent tokens to read items. */
    private suspend fun pass(vararg args: String, reason: String? = null, check: Boolean = true): String {
        val output = try {
            runProcess(listOf(cli) + args, base() + listOfNotNull(reason?.let { REASON to it.take(300) }))
        } catch (e: IOException) {
            if (!check) return ""
            throw IllegalStateException("Install pass-cli on the Server: $INSTALL", e)
        }
        return if (check) output.value("pass-cli") else if (output.ok) output.out else ""
    }

    private fun base() = mapOf("PROTON_PASS_SESSION_DIR" to sessionDir.path)

    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject

    private fun JsonElement.text(key: String) = jsonObject[key]?.jsonPrimitive?.contentOrNull.orEmpty()

    private companion object {
        const val TOKEN = "PROTON_PASS_PERSONAL_ACCESS_TOKEN"
        const val REASON = "PROTON_PASS_AGENT_REASON"
        const val INSTALL = "brew install protonpass/tap/pass-cli"
        val INSTRUCTIONS = Regex("""$TOKEN="([^"]+)"""")

        fun executable() = listOf("${System.getProperty("user.home")}/.local/bin/pass-cli", "/opt/homebrew/bin/pass-cli", "/usr/local/bin/pass-cli")
            .firstOrNull { File(it).canExecute() } ?: "pass-cli"
    }
}
