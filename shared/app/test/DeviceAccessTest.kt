package kinuvo.app

import kinuvo.app.connection.*
import kinuvo.app.device.*

import kinuvo.protocol.*
import kinuvo.client.KinuvoClient
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class DeviceAccessTest {
    private class Prefs : Preferences {
        val values = mutableMapOf<String, String>()
        override fun get(key: String) = values[key]
        override fun set(key: String, value: String?) { if (value == null) values.remove(key) else values[key] = value }
    }
    private class Reader : DeviceReader {
        override val kinds = listOf("Contacts")
        var callback: ((String) -> Unit)? = null
        override fun authorize(kind: String, completion: (String?) -> Unit) = completion(null)
        override fun read(requestJson: String, completion: (String) -> Unit) { callback = completion }
    }

    @Test
    fun accessDefaultsOffAndIsSavedPerServer() = runTest {
        val prefs = Prefs()
        val reader = Reader()
        val access = DeviceAccess(prefs, reader)
        val client = KinuvoClient("ws://unused", "test")
        try {
            access.attach("one", client)
            assertTrue(access.tools("one").isEmpty())
            access.setEnabled(DeviceToolKind.Contacts, true)
            assertEquals(setOf(DeviceToolKind.Contacts), access.tools("one"))
            assertTrue(access.tools("two").isEmpty())
            assertEquals(access.tools("one"), DeviceAccess(prefs, reader).tools("one"))
        } finally { client.close() }
    }

    @Test
    fun abandonedAuthorizationDoesNotBlockOrEnableAnotherServer() = runTest {
        val access = DeviceAccess(Prefs(), Reader())
        val client = KinuvoClient("ws://unused", "test")
        try {
            access.attach("one", client)
            val abandoned = access.authorizing(DeviceToolKind.Contacts)
            access.cancelAuthorization()
            assertNull(access.state.value.busy)
            access.authorized(abandoned, null)
            assertTrue(access.tools("one").isEmpty())
            val switched = access.authorizing(DeviceToolKind.Contacts)
            access.attach("two", client)
            access.authorized(switched, null)
            assertTrue(access.tools("two").isEmpty())
            val current = access.authorizing(DeviceToolKind.Contacts)
            access.authorized(switched, "Denied")
            assertEquals(DeviceToolKind.Contacts, access.state.value.busy)
            access.authorized(current, null)
            assertEquals(setOf(DeviceToolKind.Contacts), access.tools("two"))
        } finally { client.close() }
    }

    @Test
    fun disablingDuringAReadWithholdsTheResult() = runTest {
        val reader = Reader()
        val access = DeviceAccess(Prefs(), reader)
        val client = KinuvoClient("ws://unused", "test")
        try {
            access.attach("one", client)
            access.setEnabled(DeviceToolKind.Contacts, true)
            val result = async(start = CoroutineStart.UNDISPATCHED) { access.read("one", DeviceToolRequest(DeviceToolKind.Contacts, query = "Ada")) }
            access.setEnabled(DeviceToolKind.Contacts, false)
            reader.callback!!.invoke(ProtocolJson.encodeToString(DeviceToolResult.serializer(), DeviceToolResult(DeviceToolStatus.Ready)))
            assertEquals(DeviceToolStatus.Denied, result.await().status)
        } finally { client.close() }
    }
}
