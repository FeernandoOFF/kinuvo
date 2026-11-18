package kinuvo.protocol.device

import kotlin.test.*

class TailcatLinksTest {
    @Test fun roundTripAndBounds() {
        val invite = "kinuvo1:" + "aB_9-".repeat(80)
        assertTrue(Links.isTailcatInvite(invite))
        assertEquals(PairLink(invite, ""), Links.parsePair(Links.tailcatPair(invite)))
        for (bad in listOf("kinuvo1:", "kinuvo1:short", "tailpocket1:" + "a".repeat(100), "kinuvo1:!" + "a".repeat(100), "kinuvo1:" + "a".repeat(4096), "kinuvo1:" + "a".repeat(100) + "/")) {
            assertFalse(Links.isTailcatInvite(bad), bad.take(20))
            assertNull(Links.parsePair("kinuvo://pair?tailcat=$bad"))
            assertFailsWith<IllegalArgumentException> { Links.tailcatPair(bad) }
        }
        assertEquals(PairLink("https://home.example", "123456"), Links.parsePair(Links.pair("https://home.example", "123456")))
        assertEquals(PairLink("https://home.example", "123456"), Links.parsePair(Links.webPair("https://home.example", "123456")))
    }
}
