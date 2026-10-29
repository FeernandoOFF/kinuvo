package kinuvo.harness.server

import kinuvo.client.KinuvoClient
import kinuvo.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.io.files.Path
import java.nio.file.Files
import kotlin.test.*

class ExecutionEventAccessTest {
    @Test fun executionCatalogEventsRequireManagementAccess() = runBlocking {
        val dir = Files.createTempDirectory("kinuvo-execution-access").toFile()
        val server = HarnessServer(Harness(home = Path(dir.path)))
        var manager: KinuvoClient? = null
        var device: KinuvoClient? = null
        try {
            val port = server.start("127.0.0.1", 0, schedule = false)
            val admin = KinuvoClient("ws://127.0.0.1:$port/ws", "manager", pairingCode = server.auth.pairing(false, true)!!.code)
            manager = admin
            admin.connect()
            val restricted = KinuvoClient("ws://127.0.0.1:$port/ws", "device", pairingCode = server.auth.pairing(false, false)!!.code)
            device = restricted
            restricted.connect()
            assertFails { restricted.call(Api.ExecutionCatalog, Unit) }
            supervisorScope {
                val allowed = async(start = CoroutineStart.UNDISPATCHED) { admin.events(Api.ExecutionCatalogChanged).first() }
                val forbidden = async(start = CoroutineStart.UNDISPATCHED) { restricted.events(Api.ExecutionCatalogChanged).first() }
                admin.call(Api.SaveHarnessConnection, HarnessConnection("private", "Private", ExecutionConnection.Acp(listOf("private-command", "private-argument"))))
                assertEquals("private", withTimeout(5_000) { allowed.await() }.connections.single().config.id)
                assertNull(withTimeoutOrNull(200) { forbidden.await() })
                forbidden.cancelAndJoin()
            }
        } finally { device?.close(); manager?.close(); server.stop(); dir.deleteRecursively() }
    }
}
