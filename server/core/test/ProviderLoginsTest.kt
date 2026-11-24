package kinuvo.server

import kinuvo.protocol.model.DeviceCode
import kinuvo.protocol.model.ProviderAccount
import kinuvo.server.plugin.ProviderAuth
import kinuvo.server.providers.ModelProvider
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class ProviderLoginsTest {
    @Test fun startsCancelPreviousAndForwardAccountAndConsentChoices() = runTest {
        val calls = mutableListOf<String>()
        val auth = object : ProviderAuth {
            override suspend fun signIn(redirectUri: String, anotherAccount: Boolean, requestConsent: Boolean): String {
                calls += "start:$anotherAccount:$requestConsent"
                return "authorize"
            }
            override suspend fun cancelSignIn() { calls += "cancel" }
            override suspend fun completeSignIn(callbackUrl: String) { calls += "complete" }
            override suspend fun account() = ProviderAccount(false)
            override suspend fun login(show: (DeviceCode) -> Unit) { calls += "device"; show(DeviceCode("url", "code", 0)) }
            override suspend fun logout() { }
        }
        val provider = object : ModelProvider {
            override val id = "codex"
            override val auth = auth
        }
        var changed = 0
        val logins = ProviderLogins(listOf(provider), backgroundScope) { changed++ }
        assertEquals("authorize", logins.signIn("codex", "redirect", true, true))
        logins.cancelSignIn("codex")
        logins.signIn("codex", "redirect")
        logins.completeSignIn("codex", "callback")
        assertEquals(listOf("cancel", "start:true:true", "cancel", "cancel", "start:false:false", "complete"), calls)
        assertEquals(1, changed)
    }
}
