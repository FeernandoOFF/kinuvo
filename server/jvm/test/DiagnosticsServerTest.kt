package kinuvo.server.jvm

import kinuvo.testing.harness.TemporaryDirectories
import kinuvo.client.KinuvoClient
import kinuvo.protocol.Api
import kinuvo.protocol.diagnostics.RecordDiagnosticsParams
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiagnosticsServerTest {
    private val directories = TemporaryDirectories()
    @AfterTest fun close() = directories.close()

    @Test fun onlyManagersCanExportOrEnableRecording() = runBlocking {
        val home = directories.create("kinuvo-diagnostic-access").toFile()
        File(home, "logs").mkdirs()
        File(home, "logs/server.log").writeText("Example failure\napi_key=PRIVATE_VALUE")
        val server = JvmServerHost(JvmAgentRuntime(home = Path(home.path)))
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
            assertFails { restricted.call(Api.DiagnosticsStatus, Unit) }
            assertFails { restricted.call(Api.DiagnosticsExport, Unit) }
            assertFails { restricted.call(Api.DiagnosticsRecord, RecordDiagnosticsParams(15)) }
            assertFalse(File(home, "diagnostic-recording").exists())
            assertTrue(admin.call(Api.DiagnosticsStatus, Unit).available)
            val report = admin.call(Api.DiagnosticsExport, Unit)
            assertContains(report.files.getValue("logs/server.log"), "Example failure")
            assertFalse("PRIVATE_VALUE" in report.files.values.joinToString())
            assertTrue(admin.call(Api.DiagnosticsRecord, RecordDiagnosticsParams(15)).recordingUntil > System.currentTimeMillis())
            assertEquals(0, admin.call(Api.DiagnosticsRecord, RecordDiagnosticsParams(0)).recordingUntil)
        } finally { device?.close(); manager?.close(); server.stop() }
    }
}
