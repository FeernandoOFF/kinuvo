package kinuvo.harness.mcp

import kotlinx.serialization.Serializable

/**
 * An OAuth sign-in to the MCP server at [resource] (its URL). [oauthResource] is the resource the tokens are for, when
 * it differs; [expiresAt] is in epoch seconds; [scope] what was granted; [secretPost] sends the secret in the form.
 */
@Serializable
data class McpSignIn(
    val resource: String,
    val tokenEndpoint: String,
    val clientId: String,
    val clientSecret: String? = null,
    val accessToken: String,
    val refreshToken: String? = null,
    val expiresAt: Long? = null,
    val scope: String? = null,
    val oauthResource: String? = null,
    val secretPost: Boolean = false,
)

/** Where MCP sign-ins are kept, by sign-in ID such as `agents/<id>/<server>`. */
interface McpSignInStore {
    suspend fun get(id: String): McpSignIn?
    suspend fun put(id: String, signIn: McpSignIn)
    suspend fun remove(id: String)
    /** Every sign-in under [prefix], e.g. a deleted Agent's. */
    suspend fun removeAll(prefix: String)
}

internal fun checkSignInId(parts: List<String>) = require(parts.none { it.isEmpty() || it == "." || it == ".." }) { "Bad sign-in id '${parts.joinToString("/")}'" }
