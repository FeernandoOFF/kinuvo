package kinuvo.ui

import kinuvo.app.session.ActiveCall
import kinuvo.app.session.LiveCalls
import kinuvo.app.native.DestinationLifetime
import kinuvo.app.native.StateObservation
import org.koin.mp.KoinPlatform
import kinuvo.ui.voice.AgentInks
import kinuvo.protocol.agent.AgentId

class NativeLiveCalls {
    private val lifetime = DestinationLifetime()
    private val calls get() = KoinPlatform.getKoin().get<LiveCalls>()
    fun observe(onState: (ActiveCall?) -> Unit): StateObservation = lifetime.observe(calls.state, onState)
    fun mute(muted: Boolean) = calls.mute(muted)
    fun speaker(on: Boolean) = calls.speaker(on)
    fun openSession() = calls.openSession()
    fun end() = calls.end()
    /** The Agent's colour as 0xAARRGGBB, Kinuvo's purple until the app has shown its Agents. */
    fun ink(agentId: String): Long = AgentInks.byId[AgentId(agentId)] ?: 0xFFA98BF0L
    fun close() = lifetime.close()
}
