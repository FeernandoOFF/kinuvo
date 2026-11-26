package kinuvo.providers.codex

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.*
import kinuvo.server.plugin.Keychain
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import kotlin.test.*
import kotlin.time.Clock

class ChatGptSignInTest {
    private class Store : Keychain {
        override val name = "test"
        var text: String? = null
        override suspend fun read() = text
        override suspend fun write(token: String) { text = token }
        override suspend fun delete() { text = null }
    }
    private val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private fun b64(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    private var nonce = ""
    private var scope = "openid chatgpt.tokens.use.direct"
    private var subject = "person"
    private var audience = "oaiapp_test"
    private var expiresIn = 3600
    private var tamper = false
    private var exchanges = 0
    private var tokenError: String? = null
    private val forms = mutableListOf<Parameters>()
    private val store = Store()
    private val auth = CodexAuth(store, HttpClient(MockEngine { req ->
        when (req.url.encodedPath) {
            "/.well-known/openid-configuration" -> respond("""{"issuer":"https://auth.openai.com","jwks_uri":"https://auth.openai.com/keys","revocation_endpoint":"https://auth.openai.com/revoke"}""")
            "/revoke" -> respond("")
            "/keys" -> {
                val key = pair.public as RSAPublicKey
                respond("""{"keys":[{"kty":"RSA","kid":"key","n":"${b64(key.modulus.toByteArray().dropWhile { it == 0.toByte() }.toByteArray())}","e":"${b64(key.publicExponent.toByteArray())}"}]}""")
            }
            else -> {
                tokenError?.let { return@MockEngine respond("""{"error":"$it"}""", HttpStatusCode.BadRequest) }
                exchanges++
                forms += parseQueryString(req.body.toByteArray().decodeToString())
                val header = b64("""{"alg":"RS256","kid":"key"}""".toByteArray())
                val body = b64("""{"iss":"https://auth.openai.com","aud":"$audience","sub":"$subject","email":"me@example.com","nonce":"$nonce","exp":${Clock.System.now().epochSeconds + expiresIn}}""".toByteArray())
                val signed = "$header.$body"
                val signature = Signature.getInstance("SHA256withRSA").apply { initSign(pair.private); update(signed.toByteArray()) }.sign()
                if (tamper) signature[0] = (signature[0].toInt() xor 1).toByte()
                respond("""{"access_token":"access-$exchanges","refresh_token":"refresh-$exchanges","id_token":"$signed.${b64(signature)}","expires_in":3600,"scope":"$scope"}""")
            }
        }
    }))
    private val redirect = "http://127.0.0.1:1455/auth/callback"
    private suspend fun begin(): Url = Url(auth.signIn(redirect)).also { nonce = it.parameters["nonce"]!! }
    private fun callback(url: Url, extra: String = "&client_id=oaiapp_test&code=code") = "$redirect?state=${url.parameters["state"]}$extra"

    @Test fun registersValidatesStoresAndRefreshesWithIssuedClient() = runTest {
        val url = begin()
        assertEquals("dynamic_agent_client", url.parameters["client_id"])
        assertEquals("Kinuvo", url.parameters["agent_name_hint"])
        assertEquals("S256", url.parameters["code_challenge_method"])
        assertEquals(redirect, url.parameters["redirect_uri"])
        assertTrue(url.parameters["ext_agent_host_id"]!!.startsWith("urn:uuid:"))
        assertEquals("https://api.openai.com/v1", url.parameters["resource"])
        auth.completeSignIn(callback(url))
        assertEquals(true, auth.account().usesChatGptPlan)
        assertEquals("me@example.com", auth.account().label)
        assertEquals("access-1", auth.tokens().accessToken)
        assertEquals("oaiapp_test", forms[0]["client_id"])
        assertEquals(redirect, forms[0]["redirect_uri"])
        assertEquals("access-2", auth.tokens(true).accessToken)
        assertEquals("oaiapp_test", forms[1]["client_id"])
        assertEquals("refresh-1", forms[1]["refresh_token"])
        assertNull(forms[1]["scope"])
        val again = begin()
        assertEquals("oaiapp_test", again.parameters["client_id"])
        assertNull(again.parameters["agent_name_hint"])
        assertEquals(url.parameters["ext_agent_host_id"], again.parameters["ext_agent_host_id"])
        assertNotNull(again.parameters["id_token_hint"])
        assertNotEquals(url.parameters["state"], again.parameters["state"])
    }

    @Test fun rejectsWrongStateDeniedAndMissingRegistrationWithoutExchange() = runTest {
        val url = begin()
        assertFailsWith<IllegalStateException> { auth.completeSignIn("$redirect?state=wrong&code=x") }
        assertFailsWith<IllegalStateException> { auth.completeSignIn(callback(url, "&error=access_denied&code=x")) }
        assertFailsWith<IllegalStateException> { auth.completeSignIn(callback(begin(), "&code=x")) }
        assertEquals(0, exchanges)
    }

    @Test fun missingPlanScopeRetainsIdentityAndAllowsDeliberateConsentRetry() = runTest {
        scope = "openid"
        auth.completeSignIn(callback(begin()))
        assertTrue(auth.account().signedIn)
        assertFalse(auth.account().planAllowed)
        assertEquals("me@example.com", auth.account().label)
        assertFailsWith<IllegalStateException> { auth.tokens() }
        val retry = Url(auth.signIn(redirect, requestConsent = true))
        assertEquals("consent", retry.parameters["prompt"])
        assertEquals("oaiapp_test", retry.parameters["client_id"])
        nonce = retry.parameters["nonce"]!!
        scope = "chatgpt.tokens.use.direct"
        auth.completeSignIn(callback(retry))
        assertTrue(auth.account().planAllowed)
    }

    @Test fun rejectsInvalidSignatureExpiryAudienceAndNonce() = runTest {
        for (invalid in listOf("signature", "expiry", "audience", "nonce")) {
            tamper = invalid == "signature"
            expiresIn = if (invalid == "expiry") -1 else 3600
            audience = if (invalid == "audience") "wrong" else "oaiapp_test"
            val url = begin()
            if (invalid == "nonce") nonce = "wrong"
            assertFails { auth.completeSignIn(callback(url)) }
            assertFalse(auth.account().signedIn)
        }
    }

    @Test fun anotherAccountPreservesSeparateRegistrationsAfterLogout() = runTest {
        auth.completeSignIn(callback(begin()))
        auth.logout()
        assertEquals("oaiapp_test", begin().parameters["client_id"])
        subject = "second"
        audience = "oaiapp_second"
        val url = Url(auth.signIn(redirect, anotherAccount = true))
        nonce = url.parameters["nonce"]!!
        assertEquals("dynamic_agent_client", url.parameters["client_id"])
        assertNull(url.parameters["id_token_hint"])
        auth.completeSignIn(callback(url, "&client_id=oaiapp_second&code=code"))
        val accounts = Json.parseToJsonElement(store.text!!).jsonObject.getValue("siwc_accounts").jsonObject
        assertEquals(setOf("oaiapp_test", "oaiapp_second"), accounts.keys)
        assertEquals("person", accounts.getValue("oaiapp_test").jsonObject["subject"]!!.jsonPrimitive.content)
        assertEquals("oaiapp_second", begin().parameters["client_id"])
    }

    @Test fun switchingAnActiveLegacyRegistrationPreservesItsCredentials() = runTest {
        auth.completeSignIn(callback(begin()))
        val root = Json.parseToJsonElement(store.text!!).jsonObject
        store.text = JsonObject(root.filterKeys { it != "siwc_accounts" }).toString()
        subject = "second"
        audience = "oaiapp_second"
        val url = Url(auth.signIn(redirect, anotherAccount = true))
        nonce = url.parameters["nonce"]!!
        auth.completeSignIn(callback(url, "&client_id=oaiapp_second&code=code"))
        val accounts = Json.parseToJsonElement(store.text!!).jsonObject.getValue("siwc_accounts").jsonObject
        assertEquals("access-1", accounts.getValue("oaiapp_test").jsonObject.getValue("access_token").jsonPrimitive.content)
        assertEquals("access-2", auth.tokens().accessToken)
    }

    @Test fun supersededAndCancelledAttemptsCannotCompleteOrFillPendingCapacity() = runTest {
        val old = begin()
        repeat(20) { begin() }
        val latest = begin()
        assertFails { auth.completeSignIn(callback(old)) }
        auth.cancelSignIn()
        assertFails { auth.completeSignIn(callback(latest)) }
        assertEquals(0, exchanges)
        auth.completeSignIn(callback(begin()))
        assertTrue(auth.account().signedIn)
    }

    @Test fun failedReturningIdentityKeepsActiveCredentials() = runTest {
        auth.completeSignIn(callback(begin()))
        val before = store.text
        subject = "different"
        assertFailsWith<IllegalArgumentException> { auth.completeSignIn(callback(begin())) }
        assertEquals(before, store.text)
    }

    @Test fun terminalRefreshClearsTokensButRetainsRegistration() = runTest {
        auth.completeSignIn(callback(begin()))
        tokenError = "invalid_grant"
        assertFailsWith<IllegalStateException> { auth.tokens(true) }
        assertFalse(auth.account().signedIn)
        assertFailsWith<IllegalStateException> { auth.tokens() }
        assertEquals("oaiapp_test", begin().parameters["client_id"])
        assertFalse(store.text.orEmpty().contains("refresh-1"))
    }

    @Test fun previewRequestAndStreamFailure() {
        val request = Json.parseToJsonElement(chatGptPlanRequest("""{"model":"m","temperature":1,"max_output_tokens":20,"previous_response_id":"r","metadata":{},"input":[{"role":"system","content":"hi"}],"tools":[{"type":"tool_search"},{"type":"function","name":"test"}]}""")!!).jsonObject
        assertEquals(JsonPrimitive(false), request["store"])
        assertEquals(JsonPrimitive(true), request["stream"])
        assertNull(request["temperature"])
        assertNull(request["previous_response_id"])
        assertNull(request["max_output_tokens"])
        assertEquals("developer", request["input"]!!.jsonArray[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("namespace", request["tools"]!!.jsonArray.single().jsonObject["type"]!!.jsonPrimitive.content)
        assertTrue(chatGptPlanFailure("""{"type":"response.failed","response":{"error":{"code":"subscription_sharing_usage_limit_exceeded"}}}""")!!.contains("usage limit reached"))
    }
}
