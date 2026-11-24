package kinuvo.providers.codex

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.RSA
import dev.whyoleg.cryptography.algorithms.SHA256
import dev.whyoleg.cryptography.random.CryptographyRandom
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Parameters
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.isSuccess
import io.ktor.http.parameters
import kinuvo.server.plugin.Keychain
import kinuvo.server.plugin.sha256
import kotlinx.serialization.json.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.io.encoding.Base64
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** State and PKCE remain on the Server; only the browser callback travels back from the Device. */
internal class ChatGptSignIn(private val store: Keychain, private val http: HttpClient, private val issuer: String) {
    private data class Pending(val redirect: String, val verifier: String, val nonce: String, val client: String?, val subject: String?, val workspace: String?, val expires: Long)
    private val pending = mutableMapOf<String, Pending>()
    private val now get() = Clock.System.now().epochSeconds

    suspend fun root() = store.read()?.let { Json.parseToJsonElement(it).jsonObject } ?: JsonObject(emptyMap())
    suspend fun credentials() = root()["siwc"] as? JsonObject
    private suspend fun save(root: JsonObject) = store.write(root.toString())

    fun cancel() { pending.clear() }

    suspend fun begin(redirect: String, anotherAccount: Boolean = false, requestConsent: Boolean = false): String {
        require(Regex("http://127\\.0\\.0\\.1:[0-9]+/auth/callback").matches(redirect) && Url(redirect).port in 1..65535) { "Sign-in requires a 127.0.0.1 loopback redirect at /auth/callback" }
        val root = root()
        val host = root.str("ext_agent_host_id") ?: "urn:uuid:${Uuid.random()}".also {
            save(JsonObject(root + ("ext_agent_host_id" to JsonPrimitive(it))))
        }
        val saved = if (anotherAccount) null else root["siwc"] as? JsonObject
        val state = random()
        val verifier = random()
        val nonce = random()
        pending.clear()
        pending[state] = Pending(redirect, verifier, nonce, saved?.str("client_id"), saved?.str("subject"), saved?.str("workspace"), now + 600)
        return URLBuilder("$issuer/api/accounts/authorize").apply {
            parameters.append("client_id", saved?.str("client_id") ?: "dynamic_agent_client")
            if (saved == null) parameters.append("agent_name_hint", "Kinuvo")
            parameters.append("ext_agent_host_id", host)
            saved?.str("id_token")?.let { parameters.append("id_token_hint", it) }
            saved?.str("email")?.let { parameters.append("login_hint", it) }
            if (requestConsent) parameters.append("prompt", "consent")
            parameters.append("response_type", "code")
            parameters.append("redirect_uri", redirect)
            parameters.append("scope", "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct")
            parameters.append("resource", RESOURCE)
            parameters.append("state", state)
            parameters.append("nonce", nonce)
            parameters.append("code_challenge_method", "S256")
            parameters.append("code_challenge", b64(sha256(verifier.encodeToByteArray())))
        }.buildString()
    }

    suspend fun complete(callback: String) {
        val url = Url(callback.trim())
        val query = url.parameters
        val p = pending.remove(query["state"])?.takeIf { it.expires >= now } ?: error("Sign-in expired or state did not match; start again")
        require(callback.trim().substringBefore('?') == p.redirect) { "Sign-in callback URI does not match" }
        query["error"]?.let { error(if (it == "access_denied") "ChatGPT sign-in was declined. Enable ChatGPT plan usage when signing in again." else "ChatGPT sign-in failed: $it") }
        val client = p.client ?: query["client_id"]?.takeIf { it.startsWith("oaiapp_") } ?: error("Registration returned no issued client_id")
        require(query["client_id"] == null || query["client_id"] == client) { "Sign-in returned a different client_id" }
        // Retain the issued registration even when its one-use code needs to be retried.
        if (p.client == null && credentials() == null) save(JsonObject(root() + ("siwc" to buildJsonObject { put("client_id", client) })))
        val token = exchange(parameters {
            append("grant_type", "authorization_code")
            append("client_id", client)
            append("code", query["code"] ?: error("Sign-in returned no code"))
            append("code_verifier", p.verifier)
            append("redirect_uri", p.redirect)
            append("resource", RESOURCE)
        })
        val idToken = token.str("id_token") ?: error("Sign-in returned no ID token")
        val identity = validate(idToken, client, p.nonce)
        val subject = identity.str("sub")?.takeIf { it.isNotBlank() } ?: error("ID token has no subject")
        require(p.subject == null || p.subject == subject) { "Sign-in returned a different account" }
        val workspace = identity["https://api.openai.com/auth"]?.jsonObject?.get("chatgpt_account_id")
        require(p.workspace == null || p.workspace == (workspace as? JsonPrimitive)?.contentOrNull) { "Sign-in returned a different workspace" }
        val existing = (root()["siwc_accounts"] as? JsonObject)?.get(client) as? JsonObject
        require(existing?.str("subject") == null || existing.str("subject") == subject) { "Registration belongs to a different account" }
        val record = JsonObject(token + mapOf("selected" to JsonPrimitive(true), "client_id" to JsonPrimitive(client), "subject" to JsonPrimitive(subject),
            "email" to (identity["email"] ?: JsonNull), "workspace" to (workspace ?: JsonNull), "expires_at" to JsonPrimitive(now + token.getValue("expires_in").jsonPrimitive.long)))
        saveRecord(record)
    }

    suspend fun refresh(old: JsonObject): JsonObject {
        val token = exchange(parameters {
            append("grant_type", "refresh_token")
            append("client_id", old.getValue("client_id").jsonPrimitive.content)
            append("refresh_token", old.getValue("refresh_token").jsonPrimitive.content)
            append("resource", RESOURCE)
        })
        check(token.str("refresh_token") != null) { "ChatGPT refresh returned no replacement refresh token" }
        val record = JsonObject(old + token + ("expires_at" to JsonPrimitive(now + token.getValue("expires_in").jsonPrimitive.long)))
        saveRecord(record)
        return record
    }

    suspend fun logout() {
        pending.clear()
        val root = root()
        val old = root["siwc"] as? JsonObject
        var revoked = true
        if (old?.str("refresh_token") != null) {
            revoked = try {
                val discovery = getJson("$issuer/.well-known/openid-configuration")
                val endpoint = discovery.str("revocation_endpoint") ?: error("No revocation endpoint")
                require(Url(endpoint).protocol.name == "https" && Url(endpoint).host == Url(issuer).host)
                var success = false
                for (attempt in 0..2) {
                    val response = try { http.submitForm(endpoint, parameters {
                        append("token", old.getValue("refresh_token").jsonPrimitive.content)
                        append("token_type_hint", "refresh_token")
                        append("client_id", old.getValue("client_id").jsonPrimitive.content)
                    }) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
                    if (response?.status?.isSuccess() == true) { success = true; break }
                    if (response != null && response.status.value < 500) break
                    if (attempt < 2) delay(200L shl attempt)
                }
                success
            } catch (e: CancellationException) { throw e } catch (_: Exception) { false }
        }
        // Keep the registration and host after local sign-out, but no tokens or CLI credentials.
        val mapping = old?.filterKeys { it in setOf("client_id", "subject", "email", "workspace") }
        save(buildJsonObject {
            root["ext_agent_host_id"]?.let { put("ext_agent_host_id", it) }
            val accounts = (root["siwc_accounts"] as? JsonObject).orEmpty().toMutableMap()
            mapping?.let {
                put("siwc", JsonObject(it))
                old?.str("client_id")?.let { client -> accounts[client] = JsonObject(it) }
            }
            put("siwc_accounts", JsonObject(accounts))
        })
        check(revoked) { "Signed out locally; remote revocation was not confirmed. Disconnect Kinuvo in ChatGPT Settings." }
    }

    private suspend fun validate(jwt: String, client: String, nonce: String): JsonObject {
        val parts = jwt.split('.')
        require(parts.size == 3) { "Invalid ID token" }
        val header = Json.parseToJsonElement(decode(parts[0]).decodeToString()).jsonObject
        require(header.str("alg") == "RS256") { "Unsupported ID token signature" }
        val discovery = getJson("$issuer/.well-known/openid-configuration")
        require(discovery.str("issuer") == issuer) { "Unexpected OAuth issuer" }
        val jwks = discovery.str("jwks_uri") ?: error("No JWKS endpoint")
        require(Url(jwks).protocol.name == "https" && Url(jwks).host == Url(issuer).host) { "Untrusted JWKS endpoint" }
        val key = getJson(jwks).getValue("keys").jsonArray.map { it.jsonObject }.single {
            it.str("kid") == header.str("kid") && it.str("kty") == "RSA" && (it.str("use") == null || it.str("use") == "sig")
        }
        CryptographyProvider.Default.get(RSA.PKCS1).publicKeyDecoder(SHA256).decodeFromByteArray(RSA.PublicKey.Format.JWK, key.toString().encodeToByteArray())
            .signatureVerifier().verifySignature("${parts[0]}.${parts[1]}".encodeToByteArray(), decode(parts[2]))
        val claims = Json.parseToJsonElement(decode(parts[1]).decodeToString()).jsonObject
        val aud = claims["aud"]
        require(claims.str("iss") == issuer && (aud == JsonPrimitive(client) || (aud is JsonArray && JsonPrimitive(client) in aud)) &&
            claims.getValue("exp").jsonPrimitive.long > now && claims.str("nonce") == nonce) { "ID token identity, audience, expiry or nonce did not match" }
        return claims
    }

    private suspend fun exchange(params: Parameters): JsonObject {
        val response = http.submitForm("$issuer/api/accounts/oauth/token", params)
        if (!response.status.isSuccess()) {
            val code = runCatching { Json.parseToJsonElement(response.bodyAsText()).jsonObject.str("error") }.getOrNull()
            if (params["grant_type"] == "refresh_token" && code in setOf("invalid_grant", "invalid_refresh_token", "token_expired", "refresh_token_expired", "refresh_token_invalidated", "refresh_token_reused")) {
                val root = root()
                val old = root["siwc"]?.jsonObject
                if (old != null) saveRecord(JsonObject(old.filterKeys { it in setOf("client_id", "subject", "email", "workspace", "selected") }))
            }
            error("ChatGPT token exchange failed (${response.status.value}${code?.let { ", $it" }.orEmpty()}); sign in again")
        }
        return Json.parseToJsonElement(response.bodyAsText()).jsonObject.also {
            check(it.str("access_token") != null) { "Token response has no access token" }
        }
    }
    private suspend fun getJson(url: String): JsonObject {
        val res = http.get(url)
        check(res.status.isSuccess()) { "Could not load OAuth identity metadata" }
        return Json.parseToJsonElement(res.bodyAsText()).jsonObject
    }
    private suspend fun saveRecord(record: JsonObject) {
        val root = root()
        val accounts = (root["siwc_accounts"] as? JsonObject).orEmpty().toMutableMap()
        (root["siwc"] as? JsonObject)?.let { old -> old.str("client_id")?.let { accounts[it] = old } }
        save(JsonObject(root + mapOf("siwc" to record, "siwc_accounts" to JsonObject(accounts + (record.getValue("client_id").jsonPrimitive.content to record)))))
    }
    private fun random() = b64(CryptographyRandom.nextBytes(32))
    private fun b64(bytes: ByteArray) = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(bytes)
    private fun decode(value: String) = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL).decode(value)
    private fun JsonObject.str(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull
    companion object { const val RESOURCE = "https://api.openai.com/v1" }
}
