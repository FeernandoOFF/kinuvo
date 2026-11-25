package kinuvo.diagnostics

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class RedactionTest {
    private fun gone(vararg secrets: String, text: String) {
        val redacted = Redaction.apply(text)
        secrets.forEach { assertFalse(it in redacted, "$it remains in: $redacted") }
    }

    @Test fun headersAndSchemes() = gone("TOP_SECRET", "COOKIE_SECRET", "BASIC_SECRET", text = """
        Authorization: Bearer TOP_SECRET_VALUE
        set-cookie: session=COOKIE_SECRET; Path=/
        retry with Basic QkFTSUNfU0VDUkVU
    """.trimIndent().replace("TOP_SECRET_VALUE", "TOP_SECRET").replace("QkFTSUNfU0VDUkVU", "BASIC_SECRET"))

    @Test fun namedCredentialsInPlainTextAndJson() {
        val text = """api_key="API_SECRET" {"access_token":"ACCESS_SECRET","refresh_token": "REFRESH_SECRET", "password":"PASS_SECRET"} clientSecret=CLIENT_SECRET authToken: AUTH_SECRET pairing_code=PAIR_SECRET"""
        gone("API_SECRET", "ACCESS_SECRET", "REFRESH_SECRET", "PASS_SECRET", "CLIENT_SECRET", "AUTH_SECRET", "PAIR_SECRET", text = text)
    }

    @Test fun keepsCountsAndIdsThatOnlyLookLikeCredentialNames() {
        val line = "run.finish session=7d8b8f3e-1c1f-4d55-9e6a-0a3c5f9b2d11 tokens=1500 inputTokens=1200 tokenCount=3 duration=830ms"
        assertEquals(line, Redaction.apply(line))
    }

    @Test fun urlsLoseTheirQueryStringAndCredentials() {
        val redacted = Redaction.apply("GET https://user:URL_SECRET@example.test/path/a?token=QUERY_SECRET&code=CODE_SECRET#frag and kinuvo://oauth/callback?code=APP_CODE&state=S")
        gone("URL_SECRET", "QUERY_SECRET", "CODE_SECRET", "APP_CODE", text = redacted)
        assertContains(redacted, "https://<redacted>@example.test/path/a?<redacted>")
        assertContains(redacted, "kinuvo://oauth/callback?<redacted>")
    }

    @Test fun emailAddresses() {
        val redacted = Redaction.apply("sign-in failed for someone.name+tag@example.co.uk (retrying)")
        assertEquals("sign-in failed for <email> (retrying)", redacted)
    }

    // Assembled so secret scanners don't flag the fake key.
    private val AwsKey = "AKIA" + "ABCDEFGHIJKLMNOP"

    @Test fun knownTokenShapesAndOpaqueBlobs() = gone(
        "sk-abcdefghijklmnopqrstuv", "ghp_abcdefghijklmnopqrstuvwxyz0123", "eyJhbGciOiJIUzI1NiJ9", AwsKey, "0123456789abcdef0123456789abcdef0123456789abcdef", // gitleaks:allow
        text = "a sk-abcdefghijklmnopqrstuv b ghp_abcdefghijklmnopqrstuvwxyz0123 c eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.sig d $AwsKey e 0123456789abcdef0123456789abcdef0123456789abcdef", // gitleaks:allow
    )

    @Test fun privateKeys() = gone("MIIEvQIBADANBgkq", text = "key:\n-----BEGIN PRIVATE KEY-----\nMIIEvQIBADANBgkq\n-----END PRIVATE KEY-----\nnext")

    @Test fun userNamesInPaths() {
        val redacted = Redaction.apply("/Users/alice/Documents/a.txt /home/bob/.kinuvo C:\\Users\\Carol\\AppData C:\\\\Users\\\\Carol\\\\x C:/Users/Carol/x")
        gone("alice", "bob", "Carol", text = redacted)
        assertContains(redacted, "/Users/<user>/Documents/a.txt")
        assertContains(redacted, "/home/<user>/.kinuvo")
    }

    @Test fun ordinaryLogLinesAreUntouched() {
        val line = "2026-11-24T10:00:00Z INFO  kinuvo.server - run.start agent=main session=7d8b8f3e-1c1f-4d55-9e6a-0a3c5f9b2d11 model=gpt-5.5 plugin=kinuvo.mcp prepare=1840ms"
        assertEquals(line, Redaction.apply(line))
    }
}
