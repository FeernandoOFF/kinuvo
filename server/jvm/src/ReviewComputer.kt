package kinuvo.server.jvm

import kinuvo.protocol.computer.ComputerResult
import kinuvo.protocol.computer.ComputerScreen
import kinuvo.protocol.device.DeviceToolKind
import kinuvo.protocol.device.DeviceToolStatus
import kinuvo.protocol.device.ToolDevice
import kotlin.io.encoding.Base64

internal object ReviewComputer {
    const val ID = "review-computer"
    private val screen by lazy {
        ComputerScreen("review-placeholder", 960, 720, title = "Computer screen preview", platform = "Demo",
            actions = emptySet(), screenshot = Base64.encode(ReviewComputer::class.java.getResourceAsStream("/review-computer.jpg")!!.use { it.readBytes() }))
    }

    suspend fun attach(runtime: JvmAgentRuntime) {
        runtime.computers.paired = { listOf(ToolDevice(ID, "Computer preview (static)", setOf(DeviceToolKind.Computer))) }
        runtime.computers.connect(ID) { request ->
            if (request.action != null) ComputerResult(DeviceToolStatus.Unsupported, "This is a static preview. No computer action was performed.")
            else ComputerResult(DeviceToolStatus.Ready, screen = screen)
        }
        runtime.computers.takeover(ID, false)
    }
}
