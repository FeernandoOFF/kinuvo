package kinuvo.providers.codex

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.parameters
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kinuvo.server.plugin.ProviderAuth
import kinuvo.protocol.model.DeviceCode
import kinuvo.protocol.model.ProviderAccount
import kotlinx.serialization.json.*
import kinuvo.server.plugin.Keychain
import kotlin.io.encoding.Base64
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.TimeSource

private val log = KotlinLogging.logger("kinuvo.codex.auth")

data class CodexTokens(val accessToken: String, val accountId: String, val usesChatGptPlan: Boolean = false)

/** ChatGPT OAuth tokens, kept like the Codex CLI's auth.json in [store]; refreshes and writes them back in place. */
class CodexAuth(
    private val store: Keychain = defaultCodexStore(),
    private val http: HttpClient = HttpClient(),
    private val issuer: String = ISSUER,
    private val tokenUrl: String = "$issuer/oauth/token",
    private val browserStore: Keychain = store,
) : ProviderAuth {
    private val mutex = Mutex()
    private val signIn = ChatGptSignIn(browserStore, http, issuer)
    override val browserSignIn = true
    override suspend fun signIn(redirectUri: String, anotherAccount: Boolean, requestConsent: Boolean) = mutex.withLock { signIn.begin(redirectUri, anotherAccount, requestConsent) }
    override suspend fun cancelSignIn() = mutex.withLock { signIn.cancel() }
    override suspend fun completeSignIn(callbackUrl: String) = mutex.withLock { signIn.complete(callbackUrl) }

    private val json = Json { prettyPrint = true }

    suspend fun tokens(forceRefresh: Boolean = false): CodexTokens = mutex.withLock {
        signIn.credentials()?.takeIf { it["selected"] == JsonPrimitive(true) }?.let { saved ->
            check("access_token" in saved) { "ChatGPT sign-in needs renewal. Continue with ChatGPT again." }
            val current = if (forceRefresh || saved.getValue("expires_at").jsonPrimitive.long - Clock.System.now().epochSeconds < 300) signIn.refresh(saved) else saved
            check(planAllowed(current)) { "ChatGPT plan usage is disabled. Enable it in Settings by signing in again." }
            return@withLock CodexTokens(current.getValue("access_token").jsonPrimitive.content, "", true)
        }
        var root = read()
        if (forceRefresh || isExpiring(root.accessToken)) root = refresh(root)
        CodexTokens(root.accessToken, root.tokens.getValue("account_id").jsonPrimitive.content)
    }

    override suspend fun account(): ProviderAccount = mutex.withLock {
        signIn.credentials()?.takeIf { it["selected"] == JsonPrimitive(true) }?.let {
            return@withLock ProviderAccount("access_token" in it, (it["email"] as? JsonPrimitive)?.contentOrNull, true, planAllowed(it))
        }
        val idToken = runCatching { read().tokens["id_token"]?.jsonPrimitive?.content }.getOrNull() ?: return ProviderAccount(false)
        ProviderAccount(true, claims(idToken)?.get("email")?.jsonPrimitive?.content)
    }

    override suspend fun logout() = mutex.withLock {
        if (browserStore !== store) store.delete()
        signIn.logout()
    }

    /** Signs in with ChatGPT via a device code (works headless), writing auth.json like `codex login --device-auth`. */
    override suspend fun login(show: (DeviceCode) -> Unit) {
        val api = "$issuer/api/accounts/deviceauth"
        val start = postJson("$api/usercode", buildJsonObject { put("client_id", CLIENT_ID) })
        check(start.status.isSuccess()) { "Device code request failed (${start.status})" }
        val device = Json.parseToJsonElement(start.bodyAsText()).jsonObject
        val deviceAuthId = device.getValue("device_auth_id").jsonPrimitive.content
        val userCode = (device["user_code"] ?: device.getValue("usercode")).jsonPrimitive.content
        val interval = (device["interval"]?.jsonPrimitive?.content?.trim()?.toLongOrNull() ?: 5).coerceAtLeast(1).seconds
        show(DeviceCode("$issuer/codex/device", userCode, (Clock.System.now() + 15.minutes).toEpochMilliseconds()))

        val deadline = TimeSource.Monotonic.markNow() + 15.minutes
        var poll = postJson("$api/token", buildJsonObject { put("device_auth_id", deviceAuthId); put("user_code", userCode) })
        while (poll.status == HttpStatusCode.Forbidden || poll.status == HttpStatusCode.NotFound) {
            check(deadline.hasNotPassedNow()) { "Device login timed out after 15 minutes" }
            delay(interval)
            poll = postJson("$api/token", buildJsonObject { put("device_auth_id", deviceAuthId); put("user_code", userCode) })
        }
        check(poll.status.isSuccess()) { "Device login failed (${poll.status})" }
        val grant = Json.parseToJsonElement(poll.bodyAsText()).jsonObject

        val res = http.submitForm(tokenUrl, parameters {
            append("grant_type", "authorization_code")
            append("code", grant.getValue("authorization_code").jsonPrimitive.content)
            append("redirect_uri", "$issuer/deviceauth/callback")
            append("client_id", CLIENT_ID)
            append("code_verifier", grant.getValue("code_verifier").jsonPrimitive.content)
        })
        check(res.status.isSuccess()) { "Device code exchange failed (${res.status})" }
        val tokens = Json.parseToJsonElement(res.bodyAsText()).jsonObject
        val idToken = tokens.getValue("id_token").jsonPrimitive.content
        val accountId = claims(idToken)?.get("https://api.openai.com/auth")?.jsonObject?.get("chatgpt_account_id")?.jsonPrimitive?.content
            ?: error("Login token has no ChatGPT account id")
        mutex.withLock {
            try { signIn.logout() } catch (e: CancellationException) { throw e } catch (_: Exception) {
                KotlinLogging.logger("CodexAuth").warn { "ChatGPT remote revocation was not confirmed during device sign-in; local session cleared" }
            }
            val old = signIn.root()
            write(buildJsonObject {
                if (browserStore === store) {
                    old["siwc_accounts"]?.let { put("siwc_accounts", it) }
                    old["ext_agent_host_id"]?.let { put("ext_agent_host_id", it) }
                    (old["siwc"] as? JsonObject)?.let { registration ->
                        put("siwc", JsonObject(registration.filterKeys { it in setOf("client_id", "subject", "email", "workspace") }))
                    }
                }
                put("auth_mode", "chatgpt")
                put("OPENAI_API_KEY", JsonNull)
                put("tokens", JsonObject(tokens.filterKeys { it in TOKEN_KEYS } + ("account_id" to JsonPrimitive(accountId))))
                put("last_refresh", Clock.System.now().toString())
            })
        }
    }

    private fun planAllowed(record: JsonObject) = "chatgpt.tokens.use.direct" in (record["scope"] as? JsonPrimitive)?.content.orEmpty().split(' ')

    private suspend fun postJson(url: String, body: JsonObject) = http.post(url) {
        contentType(ContentType.Application.Json)
        setBody(body.toString())
    }

    private suspend fun read(): JsonObject {
        val text = checkNotNull(store.read()) { "No Codex login found in ${store.name}. Sign in from Settings → Model providers or run `kinuvo login`." }
        return Json.parseToJsonElement(text).jsonObject
    }

    private suspend fun refresh(root: JsonObject): JsonObject {
        val refreshToken = root.tokens.getValue("refresh_token").jsonPrimitive.content
        val res = postJson(tokenUrl, buildJsonObject {
            put("client_id", CLIENT_ID)
            put("grant_type", "refresh_token")
            put("refresh_token", refreshToken)
            put("scope", "openid profile email")
        })
        if (!res.status.isSuccess()) log.warn { "chatgpt.token.refresh.failed status=${res.status.value}" }
        check(res.status.isSuccess()) { "Codex token refresh failed (${res.status}). Run `kinuvo login` or `codex login`." }
        val fresh = Json.parseToJsonElement(res.bodyAsText()).jsonObject
        val tokens = JsonObject(root.tokens + fresh.filterKeys { it in TOKEN_KEYS })
        val updated = JsonObject(root + mapOf("tokens" to tokens, "last_refresh" to JsonPrimitive(Clock.System.now().toString())))
        write(updated)
        return updated
    }

    private suspend fun write(root: JsonObject) = store.write(json.encodeToString(JsonObject.serializer(), root))

    private fun claims(jwt: String): JsonObject? = jwt.split(".").getOrNull(1)
        ?.let { Json.parseToJsonElement(Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL).decode(it).decodeToString()).jsonObject }

    private fun isExpiring(jwt: String): Boolean {
        val exp = claims(jwt)?.get("exp")?.jsonPrimitive?.long ?: return true
        return Instant.fromEpochSeconds(exp) - Clock.System.now() < 5.minutes
    }

    private val JsonObject.tokens get() = getValue("tokens").jsonObject
    private val JsonObject.accessToken get() = tokens.getValue("access_token").jsonPrimitive.content

    private companion object {
        const val ISSUER = "https://auth.openai.com"
        const val CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
        val TOKEN_KEYS = setOf("access_token", "refresh_token", "id_token")
    }
}
