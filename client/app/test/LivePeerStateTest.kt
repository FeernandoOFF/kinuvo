package kinuvo.app

import kinuvo.app.session.advancedTo
import kinuvo.protocol.voice.LivePeerState
import kotlin.test.Test
import kotlin.test.assertEquals

class LivePeerStateTest {
    @Test
    fun liveStatesFollowTheConnection() {
        assertEquals(LivePeerState.Connected, LivePeerState.Connecting.advancedTo(LivePeerState.Connected))
        assertEquals(LivePeerState.Connecting, LivePeerState.Connected.advancedTo(LivePeerState.Connecting))
        assertEquals(LivePeerState.Failed, LivePeerState.Connected.advancedTo(LivePeerState.Failed))
    }

    @Test
    fun failedAndClosedAreFinal() {
        for (next in LivePeerState.entries) {
            assertEquals(LivePeerState.Failed, LivePeerState.Failed.advancedTo(next))
            assertEquals(LivePeerState.Closed, LivePeerState.Closed.advancedTo(next))
        }
    }
}
