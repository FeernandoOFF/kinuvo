package kinuvo.server.plugin

import kinuvo.protocol.secret.REFERENCE
import kinuvo.protocol.secret.SecretEntry
import kinuvo.protocol.secret.SecretProviderStatus

/** A password manager that config can point into with Secret references, e.g. Proton Pass for `${pass://Vault/Item/field}`. */
interface SecretProvider {
    /** The URI scheme of its references, e.g. `pass`. */
    val scheme: String
    val name: String
    /** The value at [uri]; [reason] says what needs it, for the provider's audit log. */
    suspend fun resolve(uri: String, reason: String): String
    suspend fun status(): SecretProviderStatus
    /** Keeps the [token] it signs in with. */
    suspend fun connect(token: String)
    suspend fun disconnect()
    /** Names under [path], without values: vaults at the root, then items, then fields. */
    suspend fun browse(path: List<String>): List<SecretEntry>
}

/** Expands `${VAR}`s and Secret references in config; plugins get the Server's resolver and never see how it caches. */
interface SecretResolver {
    /** The value at the Secret reference [uri]; [reason] says what needs it. */
    suspend fun resolve(uri: String, reason: String): String

    /** [text] with the secrets still in memory masked. */
    suspend fun redact(text: String): String = text

    /**
     * [value] with `${VAR}`s from [env] and Secret references resolved for [reason], e.g. "Kinuvo: MCP server github".
     * A variable's own value can hold Secret references too, e.g. `GITHUB_TOKEN=${pass://Kinuvo/GitHub/token}` in `.env`.
     */
    suspend fun expand(value: String, env: Map<String, String>, reason: String): String = replace(value) { name, uri, m ->
        if (uri.isNotEmpty()) resolve(uri, reason)
        else env[name]?.let { v -> replace(v) { _, inner, whole -> if (inner.isNotEmpty()) resolve(inner, reason) else whole.value } }
            ?: m.groups[2]?.value ?: error("environment variable $name is not set")
    }

    /** Expands `${VAR}`s only; Secret references fail. */
    object None : SecretResolver {
        override suspend fun resolve(uri: String, reason: String): String =
            throw IllegalStateException("Can't read $uri: No secret provider for ${uri.substringBefore("://")}:// references")
    }
}

private suspend fun replace(value: String, by: suspend (name: String, uri: String, MatchResult) -> String): String {
    val out = StringBuilder()
    var last = 0
    for (m in REFERENCE.findAll(value)) {
        out.append(value, last, m.range.first).append(by(m.groupValues[1], m.groupValues[3], m))
        last = m.range.last + 1
    }
    return out.append(value, last, value.length).toString()
}

object SecretReferences {
    /** Whether [value] is a single `${…}` reference, which isn't secret itself. */
    fun isReference(value: String) = kinuvo.protocol.secret.isReference(value)

    fun hasReferences(value: String) = REFERENCE.containsMatchIn(value)

    /** Whether [value] has a Secret reference (not just `${VAR}`s). */
    fun hasSecret(value: String) = REFERENCE.findAll(value).any { it.groupValues[3].isNotEmpty() }
}
