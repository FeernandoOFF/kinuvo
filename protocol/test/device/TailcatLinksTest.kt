package kinuvo.protocol.device

import kotlin.test.*

class TailcatLinksTest {
    @Test fun roundTripAndBounds() {
        val address = "tc" + "aB_9-".repeat(28)
        assertEquals(PairLink(address, "123456"), Links.parsePair(Links.tailcatPair(address, "123456")))
        for (bad in listOf("tc", "tc!" + "a".repeat(140), "tc" + "a".repeat(2047), "tc" + "a".repeat(140) + "/")) {
            assertFalse(Links.isTailcatAddress(bad))
            assertNull(Links.parsePair("kinuvo://pair?tailcat=$bad&code=123456"))
            assertFailsWith<IllegalArgumentException> { Links.tailcatPair(bad, "123456") }
        }
        assertEquals(PairLink("https://home.example", "123456"), Links.parsePair(Links.pair("https://home.example", "123456")))
        assertEquals(PairLink("https://home.example", "123456"), Links.parsePair(Links.webPair("https://home.example", "123456")))
    }
}
