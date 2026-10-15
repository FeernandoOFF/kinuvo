package kinuvo.harness.server

import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.*
import kotlin.time.Instant
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.days

class DeviceAuthSecurityTest {
    private var time = Instant.parse("2026-10-10T00:00:00Z")
    private val file = Files.createTempDirectory("device-auth-security").resolve("devices.json").toFile()
    private val auth = DeviceAuth(file) { time }

    @Test fun storedDevicesRequireIdentityAccessAndExpiry() {
        file.writeText("""{"devices":[{"name":"incomplete","tokenSha256":"hash","pairedAt":"2026-01-01T00:00:00Z"}]}""")
        assertFailsWith<kotlinx.serialization.SerializationException> { auth.devices() }
    }

    @Test
    fun pairingIsExplicitSingleUseAndCannotBeUsedWhenDisabled() {
        assertEquals("", auth.pairingCode)
        assertNull(auth.authenticate("phone", null, "123456"))
        val code = auth.currentPairingCode()
        assertEquals(64, code.length)
        auth.pairable = false
        assertNull(auth.authenticate("phone", null, code))
        auth.pairable = true
        val token = assertNotNull(auth.authenticate("phone", null, code))
        assertNull(auth.authenticate("other", null, code))
        assertEquals("", auth.pairingCode)
        assertFalse(file.readText().contains(token))
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(file.toPath()))
    }

    @Test
    fun guessesConsumeABoundedBudgetWithoutOpeningAnotherWindow() {
        val code = auth.currentPairingCode()
        repeat(10) { assertNull(auth.authenticate("attacker", null, "wrong")) }
        assertNull(auth.authenticate("attacker", null, code))
        assertEquals("", auth.pairingCode)
        assertNull(auth.authenticate("attacker", null, code))
    }

    @Test
    fun rotationKeepsDeviceIdentityAndInvalidatesOldTokens() {
        val token = assertNotNull(auth.authenticate("owner", null, auth.currentPairingCode()))
        assertTrue(auth.management(token))
        val id = auth.paired(token).single().id
        val replacement = auth.rotate(token)
        assertFalse(auth.valid(token))
        assertTrue(auth.valid(replacement))
        assertEquals(id, auth.paired(replacement).single().id)
        val ordinary = assertNotNull(auth.authenticate("ordinary", null, auth.pairing(true, false)!!.code))
        assertFalse(auth.management(ordinary))
        auth.revoke(id)
        assertFalse(auth.valid(replacement))
        assertTrue(auth.valid(ordinary))
    }

    @Test
    fun cookieCredentialsSurviveRestartAndExpireWithTheDevice() {
        val token = assertNotNull(auth.authenticate("owner", null, auth.currentPairingCode()))
        assertEquals(90.days.inWholeSeconds.toInt(), auth.cookieMaxAge(token))
        val restarted = DeviceAuth(file) { time }
        assertTrue(restarted.valid(token))
        assertEquals(auth.cookieMaxAge(token), restarted.cookieMaxAge(token))
        assertNull(auth.cookieMaxAge(auth.sessionToken()))
        time += 12.hours
        assertTrue(auth.valid(token))
        assertEquals((90.days - 12.hours).inWholeSeconds.toInt(), auth.cookieMaxAge(token))
        val replacement = restarted.rotate(token)
        assertNull(auth.cookieMaxAge(token))
        assertEquals(90.days.inWholeSeconds.toInt(), auth.cookieMaxAge(replacement))
        time += 90.days
        assertFalse(auth.valid(token))
        assertFalse(auth.valid(replacement))
        assertNull(auth.cookieMaxAge(replacement))
    }

}
