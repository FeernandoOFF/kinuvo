package kinuvo.protocol.push

import java.security.interfaces.ECPublicKey
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WebPushTest {
    // RFC 8291, Appendix A.
    private val plaintext = WebPush.base64("V2hlbiBJIGdyb3cgdXAsIEkgd2FudCB0byBiZSBhIHdhdGVybWVsb24")
    private val server = WebPush.keyPair(WebPush.base64("yfWPiYE-n46HLnH0KqZOF1fJJU3MYrct3AELtAQ-oRw"),
        WebPush.base64("BP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8"))
    private val device = WebPush.keyPair(WebPush.base64("q1dXpw3UpT5VOmu_cf_v6ih07Aems3njxI-JWgLcM94"),
        WebPush.base64("BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4"))
    private val auth = WebPush.base64("BTBZMqHH6r4Tts7J_aSIgg")
    private val expected = WebPush.base64("DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8") +
        WebPush.base64("8pfeW0KbunFT06SuDKoJH9Ql87S1QUrdirN6GcG7sFz1y1sqLgVi1VhjVkHsUoEsbI_0LpXMuGvnzQ")

    @Test fun `encrypts the RFC vector`() {
        val sealed = WebPush.encrypt(plaintext, WebPush.encode(device.public as ECPublicKey), auth, server, WebPush.base64("DGv6ra1nlYgDCS1FRnbzlw"))
        assertEquals(WebPush.base64(expected), WebPush.base64(sealed))
    }

    @Test fun `decrypts the RFC vector`() = assertContentEquals(plaintext, WebPush.decrypt(expected, device, auth))

    @Test fun `round trips with fresh keys`() {
        val keys = WebPush.newKeyPair()
        val secret = WebPush.newAuthSecret()
        val sealed = WebPush.encrypt("hi".toByteArray(), WebPush.encode(keys.public as ECPublicKey), secret)
        assertEquals("hi", WebPush.decrypt(sealed, keys, secret).decodeToString())
        assertFailsWith<Exception> { WebPush.decrypt(sealed, keys, WebPush.newAuthSecret()) }
    }
}
