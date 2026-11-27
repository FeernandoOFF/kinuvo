package kinuvo.app

import kinuvo.app.native.NativeLiveCallEvents
import kinuvo.app.native.NativeLiveCallPeer
import kinuvo.app.native.liveCallPeer
import kinuvo.app.session.LiveCallException
import kinuvo.app.session.LiveCallLevels
import kinuvo.protocol.voice.LivePeerState
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class NativeLiveCallPeerTest {
    private class Native : NativeLiveCallPeer {
        lateinit var events: NativeLiveCallEvents
        val log = mutableListOf<String>()
        var offer: String? = "offer"
        var failure: String? = null
        override fun attach(events: NativeLiveCallEvents) { this.events = events }
        override fun createOffer(onOffer: (String) -> Unit, onFailure: (String) -> Unit) { offer?.let(onOffer) ?: onFailure(failure ?: "no") }
        override fun accept(answerSdp: String, onAccepted: () -> Unit, onFailure: (String) -> Unit) {
            log += "accept $answerSdp"
            failure?.let(onFailure) ?: onAccepted()
        }
        override fun setMuted(muted: Boolean) { log += "mute $muted" }
        override fun setSpeakerMuted(muted: Boolean) { log += "speaker $muted" }
        override fun close() { log += "close" }
    }

    @Test
    fun offersAndAnswersCrossAsSdp() = runTest {
        val native = Native()
        val peer = liveCallPeer(native)
        assertEquals("offer", peer.createOffer())
        peer.accept("answer")
        assertEquals(listOf("accept answer"), native.log)
    }

    @Test
    fun failuresReachTheCallerReadable() = runTest {
        val native = Native().apply { offer = null; failure = "No microphone" }
        val peer = liveCallPeer(native)
        assertEquals("No microphone", assertFailsWith<LiveCallException> { peer.createOffer() }.message)
        assertEquals("No microphone", assertFailsWith<LiveCallException> { peer.accept("answer") }.message)
    }

    @Test
    fun statesFollowTheNativePeerAndFailureIsFinal() {
        val native = Native()
        val peer = liveCallPeer(native)
        assertEquals(LivePeerState.Connecting, peer.state.value)
        native.events.connected()
        assertEquals(LivePeerState.Connected, peer.state.value)
        native.events.connecting()
        assertEquals(LivePeerState.Connecting, peer.state.value)
        native.events.failed()
        native.events.connected()
        assertEquals(LivePeerState.Failed, peer.state.value)
    }

    @Test
    fun levelsAreClampedAndClearedOnClose() {
        val native = Native()
        val peer = liveCallPeer(native)
        native.events.levels(2f, -1f)
        assertEquals(LiveCallLevels(1f, 0f), peer.levels.value)
        peer.close()
        native.events.levels(0.5f, 0.5f)
        assertEquals(LiveCallLevels(), peer.levels.value)
    }

    @Test
    fun closingReleasesTheNativePeerOnce() {
        val native = Native()
        val peer = liveCallPeer(native)
        peer.setMuted(true)
        peer.setSpeakerMuted(true)
        peer.close()
        peer.close()
        native.events.connected()
        assertEquals(LivePeerState.Closed, peer.state.value)
        assertEquals(listOf("mute true", "speaker true", "close"), native.log)
    }
}
