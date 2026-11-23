package kinuvo.server.jvm

import kotlinx.io.files.Path
import kinuvo.testing.harness.TemporaryDirectories
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.*
import kotlin.time.Instant
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.days
import kinuvo.server.DeviceAuth

class DeviceAuthSecurityTest {
    private val temporaryDirectories = TemporaryDirectories()

    @AfterTest
    fun deleteTemporaryDirectories() = temporaryDirectories.close()

    private var time = Instant.parse("2026-10-10T00:00:00Z")
    private val file = temporaryDirectories.create("device-auth-security").resolve("devices.json").toFile()
    private val auth = DeviceAuth(Path(file.path)) { time }

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
    fun reviewPairingIsReusableOrdinaryAndExpires() {
        val code = "review-code-".repeat(4)
        auth.reviewPairing(code, time + 30.days)
        auth.refreshCode(management = true)
        repeat(10) { assertNull(auth.authenticate("guess", null, "wrong")) }
        val first = assertNotNull(auth.authenticate("reviewer", null, code))
        val second = assertNotNull(auth.authenticate("another reviewer", null, code))
        assertNotEquals(first, second)
        assertFalse(auth.management(first))
        assertFalse(auth.management(second))
        auth.pairable = false
        assertNull(auth.authenticate("disabled", null, code))
        auth.pairable = true
        time += 30.days
        assertNull(auth.authenticate("expired", null, code))
        assertFalse(auth.valid(first))
    }

    @Test
    fun permanentReviewInviteCanPairAgainAfterDeviceTokensExpire() {
        val code = "a".repeat(64)
        auth.reviewPairing(code)
        val first = assertNotNull(auth.authenticate("reviewer", null, code))
        assertEquals(90.days.inWholeSeconds.toInt(), auth.cookieMaxAge(first))
        time += 120.days
        assertFalse(auth.valid(first))
        val restarted = DeviceAuth(Path(file.path)) { time }
        restarted.reviewPairing(code)
        assertNotNull(restarted.authenticate("another reviewer", null, code))
        assertNotNull(restarted.authenticate("reviewer again", null, code))
    }

    @Test
    fun shortReviewCodeIgnoresCaseAndSeparators() {
        auth.reviewPairing("LSBN-7K4Q")
        assertNotNull(auth.authenticate("reviewer", null, "lsbn 7k4q"))
        assertNull(auth.authenticate("guess", null, "LSBN-7K4R"))
        assertFailsWith<IllegalArgumentException> { auth.reviewPairing("ABC-123") }
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
        val rateKey = auth.rateKey(token)
        val replacement = auth.rotate(token)
        assertEquals(rateKey, auth.rateKey(replacement))
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
        val restarted = DeviceAuth(Path(file.path)) { time }
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
