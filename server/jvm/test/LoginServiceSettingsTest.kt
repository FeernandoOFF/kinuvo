package kinuvo.server.jvm

import kinuvo.testing.harness.TemporaryDirectories
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LoginServiceSettingsTest {
    private val temporaryDirectories = TemporaryDirectories()

    @AfterTest
    fun deleteTemporaryDirectories() = temporaryDirectories.close()

    private val userHome = temporaryDirectories.create("login-service").toString()

    @Test
    fun keepsWhatTheServiceServes() {
        assertNull(LoginServiceSettings.read(userHome))
        val settings = LoginServiceSettings("/data/kinuvo", ServeOptions("127.0.0.1", 8791, tailscale = true, tailscalePort = 8443))
        LoginServiceSettings.write(settings, userHome)
        assertEquals(settings, LoginServiceSettings.read(userHome))
    }
}
