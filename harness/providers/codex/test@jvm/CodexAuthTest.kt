package kinuvo.harness.codex

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import java.io.File
import java.nio.file.Files
import java.util.Base64
import kinuvo.harness.core.PrivateFile
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
    private val file = File(Files.createTempDirectory("codex").toFile(), "auth.json")
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
    fun forcedRefreshAndFailureLeavesFileUntouched() = runTest {
        writeAuth(jwt(1.hours))
        val before = file.readText()
        val e = assertFailsWith<IllegalStateException> { auth(HttpStatusCode.BadRequest).tokens(forceRefresh = true) }
        assertTrue("codex login" in e.message.orEmpty())
        assertEquals(before, file.readText())
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
