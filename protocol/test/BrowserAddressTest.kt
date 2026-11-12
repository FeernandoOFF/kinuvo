package kinuvo.protocol

import kotlin.test.*

class BrowserAddressTest {
    @Test fun resolvesHostsAndSearches() {
        assertEquals("https://youtube.com", browserAddress(" youtube.com "))
        assertEquals("https://example.com/a?b=1", browserAddress("example.com/a?b=1"))
        assertEquals("https://localhost:8080", browserAddress("localhost:8080"))
        assertEquals("http://x.test", browserAddress("http://x.test"))
        assertEquals("about:blank", browserAddress(""))
        assertEquals("https://www.google.com/search?q=kotlin+multiplatform", browserAddress("kotlin multiplatform"))
        assertEquals("https://www.google.com/search?q=cats", browserAddress("cats"))
        assertEquals("https://www.google.com/search?q=caf%C3%A9+%26+co", browserAddress("café & co"))
    }
}
