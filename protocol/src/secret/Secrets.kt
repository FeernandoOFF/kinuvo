package kinuvo.protocol.secret

import kotlinx.serialization.Serializable

/**
 * A password manager on the Server for Secret references (`${scheme://…}`): whether its CLI is [installed], whether it
 * has a token ([connected]), what the last check found ([detail]), where the token is kept ([tokenStorage]) and how many
 * names a reference has ([levels]: vault, item and field by default).
 */
@Serializable
data class SecretProviderStatus(
    val scheme: String,
    val name: String,
    val installed: Boolean = false,
    val connected: Boolean = false,
    val detail: String = "",
    val tokenStorage: String = "",
    val levels: Int = 3,
)

/** A vault, item or field name in a password manager; [kind] is e.g. an item's type. Never a value. */
@Serializable
data class SecretEntry(val name: String, val kind: String = "")

@Serializable
data class SecretProviderRef(val scheme: String)

@Serializable
data class ConnectSecretsParams(val scheme: String, val token: String)

@Serializable
data class BrowseSecretsParams(val scheme: String, val path: List<String> = emptyList())

/** A Secret reference to [path] (vault, item, field) in [scheme], with `/ % ? # }` in names percent-encoded. */
fun secretReference(scheme: String, path: List<String>) = "\${$scheme://" + path.joinToString("/") { part ->
    part.map { if (it in "/%?#}") "%" + it.code.toString(16).uppercase().padStart(2, '0') else "$it" }.joinToString("")
} + "}"

/** `${VAR}`, `${VAR:-default}` or a Secret reference `${scheme://…}`: groups name, default, uri. */
val REFERENCE = Regex("""\$\{(?:([A-Za-z_][A-Za-z0-9_]*)(?::-([^}]*))?|([a-z][a-z0-9+.-]*://[^}]+))\}""")

/** Whether [value] is a single `${…}` reference, which isn't secret itself. */
fun isReference(value: String) = REFERENCE.matchEntire(value.trim()) != null

/** The names in [uri] (`scheme://a/b/c`), with percent-encoded characters decoded. */
fun secretPath(uri: String): List<String> = uri.substringAfter("://").split("/").map { part ->
    Regex("%([0-9A-Fa-f]{2})").replace(part) { it.groupValues[1].toInt(16).toChar().toString() }
}

