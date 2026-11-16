package kinuvo.app

import kinuvo.app.connection.*
import kinuvo.app.device.*
import kotlin.test.*

class ServerBoundTest {
    @Test fun everyRepositoryTheServerOwnsIsAttachedAndDetached() {
        val owner = ConnectedServer(Notifications(emptyList()), DeviceAccess(MemoryPreferences(), object : DeviceReader {
            override val kinds = emptyList<String>()
            override fun authorize(kind: String, completion: (String?) -> Unit) = completion(null)
            override fun read(requestJson: String, completion: (String) -> Unit) = error("Not used")
        }))
        val fields = ConnectedServer::class.java.declaredFields
            .filter { ServerBound::class.java.isAssignableFrom(it.type) }
            .onEach { it.isAccessible = true }
            .map { it.get(owner) }
        assertTrue(fields.size >= 10)
        fields.forEach { field -> assertTrue(owner.bound.any { it === field }, "$field is not in ConnectedServer.bound") }
    }
}
