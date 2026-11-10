package kinuvo.harness.server

import kinuvo.server.plugin.Notification
import kinuvo.protocol.*
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class PushNotificationsTest {
    private val fixture = RuntimeFixture()
    private val pushed = mutableListOf<Notification>()
    private var devices = 1
    private var failures = emptyList<String>()
    private val push = object : DevicePush {
        override suspend fun send(notification: Notification) = Pushed(devices, failures).also { pushed += notification }
        override fun close() {}
    }

    @Test fun pushesOnlyWhileTurnedOn() = runTest {
        val notifications = Notifications(fixture.runtime(), this, routineOf = { null }, devices = push)
        notifications.on(fixture.agent.id, "s1", AgentEvent.InteractionRequested(PendingInteraction.Questions("q1", "f1", "Which flight?", emptyList())))
        advanceUntilIdle()
        assertEquals(listOf("Which flight?"), pushed.map { it.body })
        fixture.push = false
        notifications.on(fixture.agent.id, "s1", AgentEvent.InteractionRequested(PendingInteraction.Questions("q2", "f2", "Which seat?", emptyList())))
        advanceUntilIdle()
        assertEquals(1, pushed.size)
    }

    @Test fun testNotificationsReachPushedDevices() = runTest {
        val notifications = Notifications(fixture.runtime(), this, routineOf = { null }, devices = push)
        assertEquals(ServiceTestResult("Pushed to 1 Device"), notifications.test(TestServiceParams(ServiceKind.Notifications, input = "ping")))
        assertEquals("ping", pushed.single().body)
        devices = 0
        assertEquals("No paired Device receives pushes yet", notifications.test(TestServiceParams(ServiceKind.Notifications)).error)
        assertEquals("Pick a provider first", notifications.test(TestServiceParams(ServiceKind.Notifications, ServiceSettings(push = false))).error)
        failures = listOf("https://push.example: HTTP 530")
        assertEquals("Couldn't push to https://push.example: HTTP 530", notifications.test(TestServiceParams(ServiceKind.Notifications)).error)
        devices = 1
        assertEquals("Pushed to 1 Device. Couldn't push to https://push.example: HTTP 530", notifications.test(TestServiceParams(ServiceKind.Notifications)).error)
        fixture.providerTest = ServiceTestResult("Sent")
        val withNtfy = TestServiceParams(ServiceKind.Notifications, ServiceSettings("ntfy"))
        assertEquals("Sent, and pushed to 1 Device. Couldn't push to https://push.example: HTTP 530", notifications.test(withNtfy).error)
        fixture.providerTest = ServiceTestResult(error = "ntfy: HTTP 500")
        assertEquals("ntfy: HTTP 500. Pushed to 1 Device. Couldn't push to https://push.example: HTTP 530", notifications.test(withNtfy).error)
        failures = emptyList()
        assertEquals("ntfy: HTTP 500. Pushed to 1 Device", notifications.test(withNtfy).error)
        fixture.providerTest = ServiceTestResult("Sent")
        assertEquals(ServiceTestResult("Sent, and pushed to 1 Device"), notifications.test(withNtfy))
        assertEquals(8, pushed.size)
    }
}
