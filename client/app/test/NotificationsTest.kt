package kinuvo.app

import kinuvo.app.connection.*

import kotlin.test.Test
import kotlin.test.assertEquals
import kinuvo.protocol.agent.AgentId
import kinuvo.protocol.session.SessionId

class NotificationsTest {
    @Test
    fun reachesEveryNotifierEvenWhenOneFails() {
        val seen = mutableListOf<Notice>()
        val notice = Notice.RunFinished(AgentId("main"), SessionId("s1"))
        Notifications(listOf(Notifier { error("no vibrator") }, Notifier { seen += it })).post(notice)
        assertEquals(listOf<Notice>(notice), seen)
    }
}
