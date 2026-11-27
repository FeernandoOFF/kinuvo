package kinuvo.providers.codex

import kotlin.test.AfterTest
import kinuvo.testing.harness.TemporaryDirectories
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import java.io.File
import java.util.Base64
import kinuvo.server.plugin.PrivateFile
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

class CodexAuthTest {
    private val temporaryDirectories = TemporaryDirectories()

    @AfterTest
    fun deleteTemporaryDirectories() = temporaryDirectories.close()

    private val file = File(temporaryDirectories.create("codex").toFile(), "auth.json")
    private val requests = mutableListOf<JsonObject>()

    private fun jwt(expiresIn: Duration): String {
        val payload = """{"exp":${(Clock.System.now() + expiresIn).epochSeconds}}"""
        return "h." + Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray()) + ".s"
    }

    private fun writeAuth(access: String) = file.writeText(
        """{"auth_mode":"chatgpt","OPENAI_API_KEY":null,"tokens":{"id_token":"old-id","access_token":"$access","refresh_token":"old-refresh","account_id":"acct"},"last_refresh":"x"}"""
    )

    private fun auth(status: HttpStatusCode = HttpStatusCode.OK, newAccess: String = jwt(1.hours)) = CodexAuth(
        store = PrivateFile(Path(file.path)),
        http = HttpClient(MockEngine { req ->
            requests += Json.parseToJsonElement(String(req.body.toByteArray())).jsonObject
            respond("""{"access_token":"$newAccess","refresh_token":"new-refresh","id_token":"new-id","expires_in":3600}""", status)
        }),
        tokenUrl = "https://auth.test/oauth/token",
    )

    @Test
    fun freshTokenIsUsedWithoutRefresh() = runTest {
        val access = jwt(1.hours)
        writeAuth(access)
        assertEquals(CodexTokens(access, "acct"), auth().tokens())
        assertTrue(requests.isEmpty())
    }

    @Test
    fun expiringTokenIsRefreshedAndWrittenBack() = runTest {
        writeAuth(jwt(Duration.ZERO))
        val newAccess = jwt(1.hours)
        val tokens = auth(newAccess = newAccess).tokens()

        assertEquals(CodexTokens(newAccess, "acct"), tokens)
        val body = requests.single()
        assertEquals("refresh_token", body["grant_type"]?.jsonPrimitive?.content)
        assertEquals("old-refresh", body["refresh_token"]?.jsonPrimitive?.content)

        val saved = Json.parseToJsonElement(file.readText()).jsonObject
        val savedTokens = saved.getValue("tokens").jsonObject
        assertEquals("new-refresh", savedTokens["refresh_token"]?.jsonPrimitive?.content)
        assertEquals("new-id", savedTokens["id_token"]?.jsonPrimitive?.content)
        assertEquals("acct", savedTokens["account_id"]?.jsonPrimitive?.content)
        assertEquals("chatgpt", saved["auth_mode"]?.jsonPrimitive?.content)
        assertEquals(JsonNull, saved["OPENAI_API_KEY"])
        assertTrue(savedTokens["expires_in"] == null, "unknown token fields must not leak into auth.json")
    }

    @Test
    fun signInKindFollowsTheTokensThatWouldBeUsed() = runTest {
        assertEquals(ChatGptSignInKind.SignedOut, auth().signInKind())
        writeAuth(jwt(1.hours))
        assertEquals(ChatGptSignInKind.Codex, auth().signInKind())
        assertTrue(requests.isEmpty())
        file.writeText("""{"tokens":{"id_token":"id","access_token":"a","refresh_token":"r","account_id":"acct"},"siwc":{"selected":true,"access_token":"siwc","refresh_token":"r"}}""")
        assertEquals(ChatGptSignInKind.Browser, auth().signInKind())
        file.writeText("""{"siwc":{"selected":false,"access_token":"siwc"}}""")
        assertEquals(ChatGptSignInKind.SignedOut, auth().signInKind())
    }

    @Test
    fun forcedRefreshAndFailureLeavesFileUntouched() = runTest {
        writeAuth(jwt(1.hours))
        val before = file.readText()
        val e = assertFailsWith<IllegalStateException> { auth(HttpStatusCode.BadRequest).tokens(forceRefresh = true) }
        assertTrue("codex login" in e.message.orEmpty())
        assertEquals(before, file.readText())
    }

    @Test
    fun deviceLoginSurvivesRevocationFailureAndClearsBrowserTokens() = runTest {
        file.writeText("""{"ext_agent_host_id":"host","siwc":{"selected":true,"client_id":"oaiapp_saved","subject":"person","refresh_token":"secret-refresh","access_token":"secret-access"}}""")
        val id = "h." + Base64.getUrlEncoder().withoutPadding().encodeToString("""{"https://api.openai.com/auth":{"chatgpt_account_id":"fallback"}}""".toByteArray()) + ".s"
        val auth = CodexAuth(store = PrivateFile(Path(file.path)), issuer = "https://auth.test",
            http = HttpClient(MockEngine { req ->
                when (req.url.encodedPath) {
                    "/api/accounts/deviceauth/usercode" -> respond("""{"device_auth_id":"dev","user_code":"code"}""")
                    "/api/accounts/deviceauth/token" -> respond("""{"authorization_code":"code","code_verifier":"ver"}""")
                    "/oauth/token" -> respond("""{"access_token":"${jwt(1.hours)}","refresh_token":"fallback-refresh","id_token":"$id"}""")
                    else -> respond("unavailable", HttpStatusCode.ServiceUnavailable)
                }
            }))
        auth.login { }
        assertEquals("fallback", auth.tokens().accountId)
        assertFalse(file.readText().contains("secret-refresh"))
        assertFalse(file.readText().contains("secret-access"))
        assertTrue(file.readText().contains("oaiapp_saved"))
    }

    @Test
    fun browserSignOutKeepsTheSeparateCodexLogin() = runTest {
        writeAuth(jwt(1.hours))
        val browser = File(temporaryDirectories.create("home").toFile(), "chatgpt-auth.json")
        browser.writeText("""{"siwc":{"selected":true,"client_id":"oaiapp_x","subject":"s","access_token":"a","expires_at":${(Clock.System.now() + 1.hours).epochSeconds},"scope":"chatgpt.tokens.use.direct"}}""")
        val auth = CodexAuth(store = PrivateFile(Path(file.path)), http = HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) }),
            browserStore = PrivateFile(Path(browser.path)))

        auth.logout()

        assertTrue(file.exists())
        assertTrue(auth.account().signedIn)
        assertEquals("acct", auth.tokens().accountId)
        auth.logout()
        assertFalse(file.exists())
    }

    @Test
    fun deviceLoginPollsExchangesAndWritesAuthJson() = runTest {
        val claims = """{"https://api.openai.com/auth":{"chatgpt_account_id":"acct-9"}}"""
        val idToken = "h." + Base64.getUrlEncoder().withoutPadding().encodeToString(claims.toByteArray()) + ".s"
        var polls = 0
        val seen = mutableListOf<String>()
        val auth = CodexAuth(
            store = PrivateFile(Path(file.path)),
            issuer = "https://auth.test",
            http = HttpClient(MockEngine { req ->
                seen += req.url.encodedPath
                when (req.url.encodedPath) {
                    "/api/accounts/deviceauth/usercode" -> respond("""{"device_auth_id":"dev","user_code":"ABCD-1234","interval":"1"}""")
                    "/api/accounts/deviceauth/token" ->
                        if (++polls < 2) respond("", HttpStatusCode.Forbidden)
                        else respond("""{"authorization_code":"code","code_challenge":"ch","code_verifier":"ver"}""")
                    else -> {
                        val form = String(req.body.toByteArray())
                        assertTrue("grant_type=authorization_code" in form && "code_verifier=ver" in form, form)
                        respond("""{"access_token":"a","refresh_token":"r","id_token":"$idToken"}""")
                    }
                }
            }),
        )
        var shown: Pair<String, String>? = null
        assertFalse(auth.account().signedIn)
        auth.login { shown = it.url to it.code }
        assertTrue(auth.account().signedIn)

        assertEquals("https://auth.test/codex/device" to "ABCD-1234", shown)
        assertEquals(2, polls)
        assertEquals("/oauth/token", seen.last())
        val saved = Json.parseToJsonElement(file.readText()).jsonObject
        assertEquals("chatgpt", saved["auth_mode"]?.jsonPrimitive?.content)
        val tokens = saved.getValue("tokens").jsonObject
        assertEquals("acct-9", tokens["account_id"]?.jsonPrimitive?.content)
        assertEquals("r", tokens["refresh_token"]?.jsonPrimitive?.content)
        auth.logout()
        assertFalse(auth.account().signedIn)
    }
}
