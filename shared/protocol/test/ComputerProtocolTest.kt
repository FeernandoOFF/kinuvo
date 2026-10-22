package kinuvo.protocol

import kotlin.test.*

class ComputerProtocolTest {
    @Test fun oldComputersKeepBasicActionsAndNewCapabilitiesRoundTrip() {
        val old = ProtocolJson.decodeFromString<ComputerScreen>("""{"capture":"old","width":100,"height":60}""")
        assertEquals(ComputerActionKind.basic, old.actions)
        val screen = old.copy(actions = ComputerActionKind.mac)
        assertEquals(screen, ProtocolJson.decodeFromString<ComputerScreen>(ProtocolJson.encodeToString(screen)))
        val action = ComputerAction(ComputerActionKind.SetWindowFrame, window = 12, x = -20, y = 50, width = 500, height = 400)
        assertEquals(action, ProtocolJson.decodeFromString<ComputerAction>(ProtocolJson.encodeToString(action)))
    }

    @Test fun appListingsPreserveUnknownRunningStatusAndSupportedActionNames() {
        val result = ComputerResult(DeviceToolStatus.Ready, apps = listOf(ComputerApp("Notes", "example.notes")))
        assertNull(ProtocolJson.decodeFromString<ComputerResult>(ProtocolJson.encodeToString(result)).apps!!.single().running)
        val info = ComputerInfo("mac", "Mac", "macOS", true, actions = setOf("right_click", "clipboard_read"))
        assertEquals(info, ProtocolJson.decodeFromString<ComputerInfo>(ProtocolJson.encodeToString(info)))
    }
}
