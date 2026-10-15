package kinuvo.app.agent

import kinuvo.protocol.SessionStatus
import kotlin.test.Test
import kotlin.test.assertEquals

class AvatarsTest {
    @Test fun waitingOutranksRunning() {
        assertEquals(AvatarState.Idle, avatarState(emptyList()))
        assertEquals(AvatarState.Working, avatarState(listOf(SessionStatus.Idle, SessionStatus.Running)))
        assertEquals(AvatarState.NeedsYou, avatarState(listOf(SessionStatus.Running, SessionStatus.Waiting)))
    }
}
