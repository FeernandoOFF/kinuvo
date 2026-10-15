package kinuvo.app

import kinuvo.app.connection.*

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class NormalizeUrlTest {
    @Test
    fun acceptsBareHostsAndFullUrls() {
        assertEquals("ws://100.82.82.99:8787/ws", normalizeUrl("100.82.82.99"))
        assertEquals("ws://10.0.2.2:9000/ws", normalizeUrl(" 10.0.2.2:9000 "))
        assertEquals("wss://box.ts.net:443/ws", normalizeUrl("https://box.ts.net"))
        assertEquals("ws://h:1/custom", normalizeUrl("ws://h:1/custom"))
        assertEquals("ws://home:80/ws", normalizeUrl("http://home/"))
        assertEquals("ws://[::1]:8787/ws", normalizeUrl("[::1]:8787"))
        assertEquals("wss://[2001:db8::1]:443/ws", normalizeUrl("https://[2001:db8::1]"))
        assertFailsWith<IllegalArgumentException> { normalizeUrl("https://user:password@host") }
        assertFailsWith<IllegalArgumentException> { normalizeUrl("javascript://host") }
        assertFailsWith<IllegalArgumentException> { normalizeUrl("host:99999") }
    }
}
