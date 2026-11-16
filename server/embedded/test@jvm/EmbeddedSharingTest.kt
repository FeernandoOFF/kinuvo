package kinuvo.server.embedded

import kinuvo.server.plugin.TailcatServing
import kinuvo.server.plugin.TailcatTunnel
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import kotlin.test.*

class EmbeddedSharingTest {
    private class Fixture {
        var lanSaved = false
        var tailcatSaved = false
        var lanActive = false
        var pairable = false
        var address: String? = null
        var available = true
        var fail = false
        var closed = 0
        val events = mutableListOf<String>()
        private val api = object : TailcatServing {
            override fun serve(state: Path, port: Int): TailcatTunnel {
                events += "serve"
                if (fail) error("failed")
                assertEquals(1234, port)
                return object : TailcatTunnel {
                    override val address = "secret"
                    override fun close() { events += "close"; closed++ }
                }
            }
            override fun reset(state: Path) { events += "reset" }
        }
        fun controller() = EmbeddedSharing(Path("unused"), { api.takeIf { available } },
            { if (it) tailcatSaved else lanSaved },
            { tailcat, value -> if (tailcat) tailcatSaved = value else lanSaved = value },
            { lanActive = it }, { events += "listen"; 1234 }, { events += "stop" },
            { value, allowed -> address = value; pairable = allowed })
    }

    @Test fun independentOptionsPersistenceAndServiceRestart() = runBlocking {
        val f = Fixture()
        val first = f.controller()
        first.restore()
        assertEquals(EmbeddedSharing.State.Off, first.state)
        assertFalse(f.pairable)
        first.shareLan(true)
        assertTrue(f.lanActive)
        assertTrue(f.pairable)
        first.share(true)
        assertEquals("secret", f.address)
        first.shareLan(false)
        assertFalse(f.lanActive)
        assertTrue(f.pairable)
        first.close()
        assertNull(f.address)
        assertFalse(f.pairable)
        assertTrue(f.tailcatSaved)
        val restarted = f.controller()
        restarted.restore()
        assertEquals(EmbeddedSharing.State.On, restarted.state)
        assertTrue(f.pairable)
        restarted.shareLan(true)
        restarted.share(false)
        assertTrue(f.lanActive)
        assertTrue(f.pairable)
        assertFalse(f.tailcatSaved)
        restarted.shareLan(false)
        assertFalse(f.pairable)
    }

    @Test fun resetStopsBeforeDeletingAndRestartsWhenOn() = runBlocking {
        val f = Fixture()
        val sharing = f.controller()
        sharing.share(true)
        f.events.clear()
        sharing.reset()
        assertEquals(listOf("close", "stop", "reset", "listen", "serve"), f.events)
        assertTrue(sharing.enabled)
        assertTrue(f.tailcatSaved)
        sharing.share(false)
        f.events.clear()
        sharing.reset()
        assertEquals(listOf("stop", "reset"), f.events)
        assertFalse(sharing.enabled)
    }

    @Test fun missingLibraryAndFailedRestoreKeepLanAvailable() = runBlocking {
        val f = Fixture()
        f.lanSaved = true
        f.tailcatSaved = true
        f.available = false
        val missing = f.controller()
        missing.restore()
        assertEquals(EmbeddedSharing.State.Unavailable, missing.state)
        assertTrue(f.lanActive)
        f.available = true
        f.fail = true
        val failed = f.controller()
        failed.restore()
        assertEquals(EmbeddedSharing.State.Error, failed.state)
        assertTrue(f.tailcatSaved)
        assertTrue(f.lanActive)
        assertNull(f.address)
        assertTrue("stop" in f.events)
    }
}
