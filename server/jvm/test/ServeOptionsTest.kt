package kinuvo.server.jvm

import kotlin.test.*

class ServeOptionsTest {
    private fun env(vararg pairs: Pair<String, String>): (String) -> String? = pairs.toMap()::get

    @Test
    fun coolifyUrlYieldsTheFirstValidHttpsOrigin() {
        assertEquals("https://app.example.com", coolifyOrigin("https://app.example.com"))
        assertEquals("https://b.example.com", coolifyOrigin("http://a.example.com, https://b.example.com/path,https://c.example.com"))
        assertEquals("https://app.example.com:8443", coolifyOrigin("https://app.example.com:8443/"))
        for (bad in listOf(null, "", "  ", "http://a.example.com", "not a url", "https://", "https://u@a.example.com", ",,")) assertNull(coolifyOrigin(bad), bad)
    }

    @Test
    fun publicUrlPrecedenceIsOptionThenEnvThenCoolifyOnlyBehindAProxy() {
        val both = env("KINUVO_PUBLIC_URL" to "https://env.example.com", "COOLIFY_URL" to "https://coolify.example.com")
        assertEquals("https://option.example.com", publicUrl("https://option.example.com", true, both))
        assertEquals("https://env.example.com", publicUrl(null, true, both))
        assertEquals("https://env.example.com", publicUrl("  ", false, both))
        val coolify = env("COOLIFY_URL" to "https://coolify.example.com")
        assertEquals("https://coolify.example.com", publicUrl(null, true, coolify))
        assertNull(publicUrl(null, false, coolify))
        assertNull(publicUrl(null, true, env("COOLIFY_URL" to "garbage")))
        assertNull(publicUrl(null, true, env()))
    }
}
