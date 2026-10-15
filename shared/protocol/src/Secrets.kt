package kinuvo.protocol

import kotlinx.serialization.Serializable

/**
 * A password manager on the Server for Secret references (`${scheme://…}`): whether its CLI is [installed], whether it
 * has a token ([connected]), what the last check found ([detail]) and where the token is kept ([tokenStorage]).
 */
@Serializable
data class SecretProviderStatus(
    val scheme: String,
    val name: String,
    val installed: Boolean = false,
    val connected: Boolean = false,
    val detail: String = "",
    val tokenStorage: String = "",
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
