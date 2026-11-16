package kinuvo.desktop

import kinuvo.app.connection.SharingState
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class TailcatSharingTest {
    @Test fun defaultOffRestoreToggleAndReset() = runBlocking {
        var saved = false
        var active = false
        var resets = 0
        val changes = mutableListOf<Boolean>()
        val sharing = TailcatSharing({ saved }, { saved = it }, { active }, { active = it; changes += it }, { resets++ })
        sharing.restore()
        assertTrue(changes.isEmpty())
        assertEquals(SharingState.Off, sharing.state)
        sharing.share(true)
        assertTrue(saved)
        assertEquals(SharingState.On, sharing.state)
        active = false
        sharing.restore()
        assertTrue(active)
        sharing.reset()
        assertEquals(1, resets)
        sharing.share(false)
        assertFalse(saved)
        assertEquals(SharingState.Off, sharing.state)
    }

    @Test fun failedRestoreIsVisibleAndDoesNotClearPreference() = runBlocking {
        var saved = true
        var fail = true
        val sharing = TailcatSharing({ saved }, { saved = it }, { false }, { if (fail) error("unavailable") }, {})
        sharing.restore()
        assertIs<SharingState.Error>(sharing.state)
        assertTrue(saved)
        fail = false
        sharing.share(false)
        assertEquals(SharingState.Off, sharing.state)
        assertFalse(saved)
    }
}
